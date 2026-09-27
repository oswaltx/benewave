package dev.greenwave.shared.tracking

/**
 * Estimates a light's program from all kinds of [LightEvent]s: green starts
 * (end of a stop), green moments (rode through) and red moments (start of a
 * stop). For every candidate cycle it searches the green window
 * `[start, start + green)` on the cycle ring that agrees with the most events;
 * green starts count double because they are sharp.
 *
 * Guards against fitting noise the same way as the live data: the window
 * fitted on the older two thirds of the events must also explain the newest
 * third ([MIN_HELD_OUT_ACCURACY]); otherwise the light stays unknown.
 * Events from other times of day are ignored ([TIME_OF_DAY_WINDOW_SEC]),
 * because signal programs change over the day.
 */
object ObservationCycleEstimator {
    val CANDIDATE_CYCLES: List<Int> = (40..150 step 5).toList()
    const val MIN_EVENTS = 6
    const val MIN_GREEN_STARTS = 2
    const val MIN_HELD_OUT = 3
    const val MIN_HELD_OUT_ACCURACY = 0.8
    const val MIN_FIT_ACCURACY = 0.85
    const val MIN_GREEN_SEC = 5
    const val TIME_OF_DAY_WINDOW_SEC = 90 * 60L
    const val MAX_AGE_SEC = 28 * 86_400L

    /** Seconds after the green start that still count as "rode off at green start". */
    private const val START_SLACK_SEC = 4

    fun estimate(events: List<LightEvent>, nowEpochSec: Long): RhythmEstimate? {
        val relevant = events
            .filter { nowEpochSec - it.epochSec in 0..MAX_AGE_SEC }
            .filter { timeOfDayDistance(it.epochSec, nowEpochSec) <= TIME_OF_DAY_WINDOW_SEC }
            .sortedBy { it.epochSec }
        if (relevant.size < MIN_EVENTS) return null
        if (relevant.count { it.kind == LightEvent.Kind.STOP_END } < MIN_GREEN_STARTS) return null

        val cut = relevant.size * 2 / 3
        val older = relevant.subList(0, cut)
        val newer = relevant.subList(cut, relevant.size)
        if (newer.size < MIN_HELD_OUT) return null
        val trial = fit(older) ?: return null
        if (accuracy(newer, trial) < MIN_HELD_OUT_ACCURACY) return null

        val final = fit(relevant) ?: return null
        if (final.accuracy < MIN_FIT_ACCURACY) return null
        return RhythmEstimate(
            cycleLengthSec = final.cycle,
            greenStartOffsetSec = final.start,
            greenDurationSec = final.green,
            sampleCount = relevant.size,
            // Passed the held-out check; the fit accuracy is the remaining uncertainty.
            confidence = final.accuracy,
        )
    }

    private data class Fit(val cycle: Int, val start: Int, val green: Int, val accuracy: Double)

    private fun weight(kind: LightEvent.Kind) = if (kind == LightEvent.Kind.STOP_END) 2 else 1

    private fun agrees(event: LightEvent, cycle: Int, start: Int, green: Int): Boolean {
        val phase = ((event.epochSec - start) % cycle + cycle) % cycle
        return when (event.kind) {
            LightEvent.Kind.PASS_GREEN -> phase < green
            LightEvent.Kind.STOP_START -> phase >= green
            LightEvent.Kind.STOP_END -> phase <= START_SLACK_SEC
        }
    }

    private fun accuracy(events: List<LightEvent>, fit: Fit): Double {
        val total = events.sumOf { weight(it.kind) }
        val good = events.filter { agrees(it, fit.cycle, fit.start, fit.green) }.sumOf { weight(it.kind) }
        return good.toDouble() / total
    }

    /**
     * Best (cycle, start, green) by weighted agreement, using prefix sums over
     * the ring so every window is counted in O(1). On ties the first window
     * found wins, which is the shortest green: only claim green where the
     * events show it.
     */
    private fun fit(events: List<LightEvent>): Fit? {
        val total = events.sumOf { weight(it.kind) }
        if (total == 0) return null
        var best: Fit? = null
        var bestScore = -1
        for (cycle in CANDIDATE_CYCLES) {
            val pass = IntArray(2 * cycle + 1)
            val stopStart = IntArray(2 * cycle + 1)
            val greenStart = IntArray(cycle)
            for (e in events) {
                val p = ((e.epochSec % cycle) + cycle).toInt() % cycle
                when (e.kind) {
                    LightEvent.Kind.PASS_GREEN -> { pass[p + 1]++; pass[p + 1 + cycle]++ }
                    LightEvent.Kind.STOP_START -> { stopStart[p + 1]++; stopStart[p + 1 + cycle]++ }
                    LightEvent.Kind.STOP_END -> greenStart[p]++
                }
            }
            for (i in 1..2 * cycle) {
                pass[i] += pass[i - 1]
                stopStart[i] += stopStart[i - 1]
            }
            val stopStarts = stopStart[cycle]
            for (start in 0 until cycle) {
                var starts = 0
                for (k in 0..START_SLACK_SEC) starts += greenStart[(start + k) % cycle]
                for (green in MIN_GREEN_SEC..cycle - MIN_GREEN_SEC) {
                    val passesIn = pass[start + green] - pass[start]
                    val stopsIn = stopStart[start + green] - stopStart[start]
                    val score = passesIn + (stopStarts - stopsIn) + 2 * starts
                    if (score > bestScore) {
                        bestScore = score
                        best = Fit(cycle, start, green, score.toDouble() / total)
                    }
                }
            }
        }
        return best
    }

    private fun timeOfDayDistance(a: Long, b: Long): Long {
        val d = Math.floorMod(a - b, 86_400L)
        return minOf(d, 86_400L - d)
    }
}
