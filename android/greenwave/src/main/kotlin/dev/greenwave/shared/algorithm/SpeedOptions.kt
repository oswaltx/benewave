package dev.greenwave.shared.algorithm

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.math.abs
import kotlin.math.floor

/**
 * The two ways to make the next light on green: speed up to catch an earlier
 * green, or slow down to arrive in a later one. [holdKmh] is set when the
 * current speed already arrives on green; the two options are there as well
 * then (the neighbouring green phases).
 *
 * An option outside the rider's speed range comes from the wider
 * [SpeedOptionsCalculator.OUTER_RANGE] and is flagged, so it can be shown
 * dimmed instead of not at all.
 */
data class SpeedOptions(
    val slowerKmh: Double?,
    val fasterKmh: Double?,
    val holdKmh: Double?,
    val slowerOutOfRange: Boolean = false,
    val fasterOutOfRange: Boolean = false,
)

/**
 * For the next light ahead, the speeds in [SpeedRangeKmh] that arrive on green
 * form bands (one per green phase). The nearest band above the current speed
 * is the faster option, the nearest band below it the slower one. Within a
 * band the speed that also gets the most later lights on green wins, ties go
 * to the speed closest to the current one (least effort).
 *
 * Arrivals within [startMarginSec] after green starts or [endMarginSec] before
 * it ends don't count, so the options don't sit right on a phase edge.
 *
 * So that there are two options whenever possible, a side without a band in
 * [SpeedRangeKmh] takes the nearest band in [OUTER_RANGE] instead (flagged as
 * out of range). Bands inside the range always win over ones outside it.
 */
object SpeedOptionsCalculator {
    /** Still plausible on a bicycle; options beyond the rider's own range come from here. */
    val OUTER_RANGE = SpeedRangeKmh(min = 5.0, max = 35.0)

    fun compute(
        route: Route,
        currentPosition: LatLng,
        nowEpochSec: Long,
        currentSpeedKmh: Double,
        speedRange: SpeedRangeKmh,
        stepKmh: Double = 0.5,
        startMarginSec: Int = 2,
        endMarginSec: Int = 3,
    ): SpeedOptions? {
        val progress = distanceAlongPath(route.path, currentPosition)
        val ahead = route.trafficLights
            .map { it to distanceAlongPath(route.path, it.position) - progress }
            .filter { it.second > 0.0 }
            .sortedBy { it.second }
        if (ahead.isEmpty()) return null
        val (next, nextDistance) = ahead.first()

        class Candidate(val speed: Double, val hits: Int, val inRange: Boolean)
        val bands = mutableListOf<MutableList<Candidate>>()
        var inBand = false
        // Grid through speedRange.min (as before), widened step by step to OUTER_RANGE.
        val stepsBelow = floor((speedRange.min - minOf(OUTER_RANGE.min, speedRange.min)) / stepKmh + 1e-9).toInt()
        val top = maxOf(OUTER_RANGE.max, speedRange.max)
        var i = -stepsBelow
        while (speedRange.min + i * stepKmh <= top + 1e-9) {
            val speed = speedRange.min + i * stepKmh
            if (arrivesOnGreen(next, nowEpochSec, nextDistance, speed, startMarginSec, endMarginSec)) {
                val hits = ahead.count { (light, d) -> arrivesOnGreen(light, nowEpochSec, d, speed, 0, 0) }
                val inRange = speed >= speedRange.min - 1e-9 && speed <= speedRange.max + 1e-9
                if (!inBand) bands.add(mutableListOf())
                bands.last().add(Candidate(speed, hits, inRange))
                inBand = true
            } else {
                inBand = false
            }
            i++
        }

        fun inRange(band: List<Candidate>) = band.any { it.inRange }
        fun best(band: List<Candidate>): Double = band
            .filter { it.inRange }.ifEmpty { band }
            .sortedWith(compareByDescending<Candidate> { it.hits }.thenBy { abs(it.speed - currentSpeedKmh) })
            .first().speed

        val current = bands.firstOrNull { band ->
            currentSpeedKmh >= band.first().speed - stepKmh / 2 && currentSpeedKmh <= band.last().speed + stepKmh / 2
        }
        val above = bands.filter { it.first().speed > currentSpeedKmh && it !== current }
        val below = bands.filter { it.last().speed < currentSpeedKmh && it !== current }
        val faster = above.firstOrNull(::inRange) ?: above.firstOrNull()
        val slower = below.lastOrNull(::inRange) ?: below.lastOrNull()
        return SpeedOptions(
            slowerKmh = slower?.let(::best),
            fasterKmh = faster?.let(::best),
            holdKmh = current?.let(::best),
            slowerOutOfRange = slower != null && !inRange(slower),
            fasterOutOfRange = faster != null && !inRange(faster),
        )
    }

    private fun arrivesOnGreen(
        light: TrafficLight,
        nowEpochSec: Long,
        distanceMeters: Double,
        speedKmh: Double,
        startMarginSec: Int,
        endMarginSec: Int,
    ): Boolean {
        val arrival = nowEpochSec + (distanceMeters / (speedKmh / 3.6)).toLong()
        val c = light.cycleLengthSec
        val pos = (((arrival - light.greenStartOffsetSec) % c) + c) % c
        return pos >= startMarginSec && pos < light.greenDurationSec - endMarginSec
    }
}
