package dev.greenwave.shared.live

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.geo.distanceToPath
import dev.greenwave.shared.model.TrafficLight
import kotlin.math.abs

enum class SignalState { RED, AMBER, GREEN, RED_AMBER, OTHER }

/** One state change of a signal head, as a live source reports it. */
data class SignalObservation(val epochSec: Long, val state: SignalState)

/**
 * One lane connection at a signalised junction (Hamburg's model): from the stop
 * line of an ingress lane to the start of an egress lane, with the recent
 * state changes of the signal that controls it.
 */
data class LaneConnection(
    val id: String,
    val laneType: String,
    val stopLine: LatLng,
    val exit: LatLng,
    val observations: List<SignalObservation>,
    /** Times the controller's cycle second went back to 0, if the source reports it. */
    val cycleStarts: List<Long> = emptyList(),
)

/**
 * Turns recent state changes into the fixed-time model the optimizer uses.
 *
 * The cycle comes from [cycleStarts] (the controller's cycle second resetting
 * to 0, Hamburg's "Wellensekunde") when available, otherwise from the gaps
 * between green starts, which then must be regular. Many signals run a fixed
 * cycle but shift green by a few seconds with demand; so the model keeps only
 * the part of the phase that was green in most observed cycles (start at the
 * [SAFE_QUANTILE] of green starts, end at the complementary quantile of green
 * ends). If that safe window is shorter than [MIN_SAFE_GREEN_SEC], or data is
 * missing or stale, the result is null and the light stays "unknown".
 */
object LiveSignalCycle {
    const val MIN_CYCLES = 3
    const val TOLERANCE_SEC = 2
    const val MIN_CONSISTENCY = 0.75
    const val SAFE_QUANTILE = 0.8
    const val MIN_SAFE_GREEN_SEC = 4

    const val MIN_HIT_RATE = 0.8
    const val MIN_CHECKED_WINDOWS = 3

    /**
     * The model from all data, but only if a model built from the older two
     * thirds predicts the newest third well: at least [MIN_HIT_RATE] of its
     * predicted green windows must lie fully inside a real green.
     */
    fun estimate(
        position: LatLng,
        observations: List<SignalObservation>,
        nowEpochSec: Long,
        cycleStarts: List<Long> = emptyList(),
    ): TrafficLight? {
        val sorted = observations.filter { it.epochSec <= nowEpochSec + 60 }.sortedBy { it.epochSec }
        if (sorted.size < 2) return null
        val split = sorted.first().epochSec + (sorted.last().epochSec - sorted.first().epochSec) * 2 / 3
        val older = model(position, sorted.filter { it.epochSec <= split }, split, cycleStarts.filter { it <= split })
            ?: return null
        val recentGreens = greens(sorted.filter { it.epochSec > split - 1 })
        var hits = 0
        var checked = 0
        val c = older.cycleLengthSec.toLong()
        var t = split - (((split - older.greenStartOffsetSec) % c) + c) % c
        while (t + older.greenDurationSec <= sorted.last().epochSec) {
            if (t > split) {
                checked++
                if (recentGreens.any { (s, e) -> s <= t && e >= t + older.greenDurationSec }) hits++
            }
            t += c
        }
        if (checked < MIN_CHECKED_WINDOWS || hits < checked * MIN_HIT_RATE) return null
        return model(position, sorted, nowEpochSec, cycleStarts)
    }

    private fun greens(sorted: List<SignalObservation>): List<Pair<Long, Long>> {
        val greens = mutableListOf<Pair<Long, Long>>()
        var greenSince: Long? = null
        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1].state
            val current = sorted[i]
            if (current.state == SignalState.GREEN && previous != SignalState.GREEN) {
                greenSince = current.epochSec
            } else if (previous == SignalState.GREEN && current.state != SignalState.GREEN) {
                greenSince?.let { greens.add(it to current.epochSec) }
                greenSince = null
            }
        }
        return greens
    }

    private fun model(
        position: LatLng,
        sorted: List<SignalObservation>,
        nowEpochSec: Long,
        cycleStarts: List<Long>,
    ): TrafficLight? {
        if (sorted.isEmpty()) return null
        val greens = greens(sorted)
        if (greens.size < MIN_CYCLES) return null

        val resets = cycleStarts.filter { it <= nowEpochSec + 60 }.sorted()
        val cycle: Long
        val anchor: Long
        if (resets.size >= MIN_CYCLES) {
            cycle = median(resets.zipWithNext { a, b -> b - a }.filter { it in 20..300 }.ifEmpty { return null })
            anchor = resets.last()
        } else {
            val starts = greens.map { it.first }
            val gaps = starts.zipWithNext { a, b -> b - a }
            cycle = median(gaps)
            if (cycle <= 0) return null
            val consistent = gaps.count { abs(it - cycle) <= TOLERANCE_SEC }.toDouble() / gaps.size
            if (consistent < MIN_CONSISTENCY) return null
            anchor = starts.last()
        }
        if (cycle !in 20..300) return null
        // Data that stopped arriving (outage, program switch to dark) is no basis.
        if (nowEpochSec - sorted.last().epochSec > 3 * cycle) return null

        // Phase positions relative to the anchor, re-centred around the first
        // green start so windows crossing the cycle boundary don't wrap.
        fun mod(x: Long) = ((x % cycle) + cycle) % cycle
        val reference = mod(greens.first().first - anchor)
        fun rel(t: Long) = mod(t - anchor - reference + cycle / 2) - cycle / 2
        val startsRel = greens.map { rel(it.first) }.sorted()
        val endsRel = greens.map { (s, e) -> rel(s) + (e - s) }.sorted()
        val safeStart = quantile(startsRel, SAFE_QUANTILE)
        val safeEnd = quantile(endsRel, 1 - SAFE_QUANTILE)
        val green = safeEnd - safeStart
        if (green < MIN_SAFE_GREEN_SEC || green >= cycle) return null
        return TrafficLight(
            position = position,
            cycleLengthSec = cycle.toInt(),
            greenStartOffsetSec = mod(anchor + reference + safeStart).toInt(),
            greenDurationSec = green.toInt(),
        )
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun quantile(sorted: List<Long>, q: Double): Long =
        sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
}

/**
 * Picks the lane connection a cyclist on [path] uses at the light near
 * [lightPosition]: stop line and exit both on the path, in riding order, close
 * to the light. Bicycle lanes win over mixed lanes, those over car lanes;
 * pedestrian crossings never count.
 */
object LaneConnectionMatcher {
    const val MAX_OFF_PATH_METERS = 20.0
    const val MAX_FROM_LIGHT_METERS = 60.0

    fun match(path: List<LatLng>, lightPosition: LatLng, connections: List<LaneConnection>): LaneConnection? {
        val lightAt = distanceAlongPath(path, lightPosition)
        return connections
            .filter { rank(it.laneType) != null }
            .filter { distanceMeters(it.stopLine, lightPosition) <= MAX_FROM_LIGHT_METERS }
            .filter {
                distanceToPath(path, it.stopLine) <= MAX_OFF_PATH_METERS &&
                    distanceToPath(path, it.exit) <= MAX_OFF_PATH_METERS
            }
            .filter {
                val s = distanceAlongPath(path, it.stopLine)
                val e = distanceAlongPath(path, it.exit)
                s < e && abs(s - lightAt) <= MAX_FROM_LIGHT_METERS
            }
            .minWithOrNull(
                compareBy<LaneConnection> { rank(it.laneType) }
                    .thenBy { distanceToPath(path, it.stopLine) + distanceToPath(path, it.exit) },
            )
    }

    private fun rank(laneType: String): Int? = when {
        laneType.contains("Fußgänger", ignoreCase = true) && !laneType.contains("Rad", ignoreCase = true) -> null
        laneType.equals("Radfahrer", ignoreCase = true) -> 0
        laneType.contains("Rad", ignoreCase = true) -> 1
        laneType.contains("KFZ", ignoreCase = true) -> 2
        else -> null
    }
}
