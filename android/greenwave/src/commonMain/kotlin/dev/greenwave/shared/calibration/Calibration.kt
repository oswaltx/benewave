package dev.greenwave.shared.calibration

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import dev.greenwave.shared.tracking.ObservationCycleEstimator
import dev.greenwave.shared.tracking.RhythmEstimate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Manual calibration of one traffic light ("Nerd-Modus", David 14.10.2026): someone
 * standing at the light taps at every change to green and to red. Tap times come from a
 * monotonic clock, relative to the session start, and are tied to wall-clock time once
 * ([Session.startEpochMs]), so a clock adjustment during the measurement changes nothing.
 *
 * Unlike the scattered observations of rides, consecutive taps show the cycle itself, so a
 * steady measurement gives the program directly ([program]); learning it from the taps as
 * events would mix up 90 s with 45 s, as both explain green and red starts alike. Like
 * learned programs it only counts at the same time of day and for four weeks, and only
 * while the rider's own observations there don't contradict it ([learned]).
 */
object Calibration {
    /** The same button again within this time is a double tap and ignored. */
    const val DOUBLE_TAP_MS = 2_500L

    /** No real green or red phase is this short: a slip, ignored. */
    const val MIN_PHASE_MS = 3_000L

    /** A phase longer than this means a change was missed; it does not count. */
    const val MAX_PHASE_SEC = 180.0

    /** Cycles and green phases varying by at most this count as a fixed-time program. */
    const val STEADY_SPREAD_SEC = 3.0

    /** Complete cycles before a summary is shown. */
    const val MIN_CYCLES = 2

    /** Steady cycles before the measured program is used for advice. */
    const val MIN_PROGRAM_CYCLES = 3

    /** Green starts may deviate this much from the integer cycle (otherwise it is not one, and the program drifts). */
    const val MAX_START_DEVIATION_SEC = 2.0

    /** Confidence of a measured program the rider's observations could not check yet. */
    const val UNCHECKED_CONFIDENCE = 0.9

    enum class Change { GREEN, RED }

    /** One tap, [atMs] after the session start (monotonic). */
    data class Tap(val atMs: Long, val change: Change)

    data class Session(
        val light: LatLng,
        /** Direction of approach (0 = north), null if unknown: then it counts for every approach. */
        val headingDeg: Int?,
        /** Movement the measured signal is for, null if unknown. */
        val turn: LightEvent.Turn?,
        val startEpochMs: Long,
        val taps: List<Tap>,
    ) {
        fun epochMs(tap: Tap): Long = startEpochMs + tap.atMs

        /** Measured for this approach and turn, seen from this kind of way; as [LightEvent.sameMovement] and [LightEvent.sameSide]. */
        fun matches(approachDeg: Double?, turn: LightEvent.Turn?, sideway: Boolean?): Boolean =
            LightEvent(light, startEpochMs / 1000, LightEvent.Kind.STOP_END, headingDeg, exitHeading(headingDeg, this.turn))
                .let { it.sameMovement(approachDeg, turn) && it.sameSide(sideway) }
    }

    /** One phase between two taps; [green] true for a green phase, false for red. */
    data class Phase(val green: Boolean, val fromMs: Long, val sec: Double) {
        val plausible: Boolean get() = sec <= MAX_PHASE_SEC
    }

    data class Summary(
        /** Medians over the plausible phases, null while none was measured. */
        val greenSec: Double?,
        val redSec: Double?,
        /** Median cycle (green start to green start), null before the first complete one. */
        val cycleSec: Double?,
        val cycles: Int,
        /** Largest difference between the cycles and between the green phases. */
        val cycleSpreadSec: Double,
        val greenSpreadSec: Double,
        /** Phases over [MAX_PHASE_SEC] and taps of the same change in a row (a change was missed). */
        val skipped: Int,
    ) {
        /** Enough cycles, and they hardly vary: looks like a fixed-time program. */
        val steady: Boolean
            get() = cycles >= MIN_CYCLES && cycleSpreadSec <= STEADY_SPREAD_SEC && greenSpreadSec <= STEADY_SPREAD_SEC

        /** Expected wait for a rider arriving at a random moment, as in SignalWait: red²/(2·cycle). */
        val expectedWaitSec: Double?
            get() {
                val cycle = cycleSec ?: return null
                val red = redSec ?: (greenSec?.let { cycle - it }) ?: return null
                return red.coerceIn(0.0, cycle).let { it * it / (2.0 * cycle) }
            }
    }

    /** Why a new tap of [change] at [atMs] is not taken, null if it is. */
    fun reject(taps: List<Tap>, change: Change, atMs: Long): String? {
        val last = taps.lastOrNull() ?: return null
        val dt = atMs - last.atMs
        return when {
            change == last.change && dt < DOUBLE_TAP_MS -> "Doppeltipp ignoriert"
            change != last.change && dt < MIN_PHASE_MS -> "Phase unter ${MIN_PHASE_MS / 1000} s ignoriert"
            else -> null
        }
    }

    /** Phases between consecutive taps of different changes; taps of the same change in a row leave a gap. */
    fun phases(taps: List<Tap>): List<Phase> = taps.zipWithNext().mapNotNull { (a, b) ->
        if (a.change == b.change) null else Phase(a.change == Change.GREEN, a.atMs, (b.atMs - a.atMs) / 1000.0)
    }

    /** Cycles from a green tap over one red tap to the next green tap, both phases plausible. */
    fun cycles(taps: List<Tap>): List<Double> = taps.windowed(3).mapNotNull { (a, b, c) ->
        val alternating = a.change == Change.GREEN && b.change == Change.RED && c.change == Change.GREEN
        val plausible = (b.atMs - a.atMs) / 1000.0 <= MAX_PHASE_SEC && (c.atMs - b.atMs) / 1000.0 <= MAX_PHASE_SEC
        if (alternating && plausible) (c.atMs - a.atMs) / 1000.0 else null
    }

    fun summary(taps: List<Tap>): Summary {
        val phases = phases(taps)
        val good = phases.filter { it.plausible }
        val greens = good.filter { it.green }.map { it.sec }
        val reds = good.filterNot { it.green }.map { it.sec }
        val cycles = cycles(taps)
        val repeats = taps.zipWithNext().count { (a, b) -> a.change == b.change }
        return Summary(
            greenSec = median(greens),
            redSec = median(reds),
            cycleSec = median(cycles),
            cycles = cycles.size,
            cycleSpreadSec = spread(cycles),
            greenSpreadSec = spread(greens),
            skipped = phases.size - good.size + repeats,
        )
    }

    /**
     * The program measured in [session]: integer cycle and green from the medians, green
     * start from the green taps. Null unless [MIN_PROGRAM_CYCLES] steady cycles were
     * measured and every green start fits the integer cycle within [MAX_START_DEVIATION_SEC].
     */
    fun program(session: Session): RhythmEstimate? {
        val s = summary(session.taps)
        if (s.cycles < MIN_PROGRAM_CYCLES || !s.steady) return null
        val starts = session.taps.zipWithNext()
            .filter { (a, b) -> a.change == Change.GREEN && b.change == Change.RED && (b.atMs - a.atMs) / 1000.0 <= MAX_PHASE_SEC }
            .map { (a, _) -> session.epochMs(a) }
        if (starts.isEmpty()) return null
        // A median of a few cycles is not exact to the second; take the integer cycle around it
        // that lines up all green starts best.
        val approx = s.cycleSec!!.roundToInt()
        val (cycle, deviations) = (approx - 1..approx + 1)
            .map { it to startDeviations(starts, it * 1000L) }
            .minBy { (_, d) -> d.maxOf { abs(it) } }
        if (deviations.any { abs(it) > MAX_START_DEVIATION_SEC }) return null
        val green = s.greenSec?.roundToInt() ?: return null
        if (green < ObservationCycleEstimator.MIN_GREEN_SEC || green > cycle - ObservationCycleEstimator.MIN_GREEN_SEC) return null
        val cycleMs = cycle * 1000L
        val offsetMs = starts.first().mod(cycleMs) + (median(deviations)!! * 1000).roundToLong()
        return RhythmEstimate(
            cycleLengthSec = cycle,
            greenStartOffsetSec = (offsetMs.mod(cycleMs) / 1000.0).roundToInt() % cycle,
            greenDurationSec = green,
            sampleCount = session.taps.size,
            confidence = 1.0,
        )
    }

    /**
     * The newest measured program among [sessions] that counts at [nowEpochSec] (same time
     * of day, not too old, as in [ObservationCycleEstimator]) and agrees with the rider's
     * [observed] events there, if there are enough of them to check; null if none.
     * [sessions] must already be those for the light and movement asked about.
     */
    fun learned(sessions: List<Session>, observed: List<LightEvent>, nowEpochSec: Long): RhythmEstimate? {
        val check = ObservationCycleEstimator.relevant(observed, nowEpochSec)
        return sessions
            .filter { ObservationCycleEstimator.inWindow(it.startEpochMs / 1000, nowEpochSec) }
            .sortedByDescending { it.startEpochMs }
            .firstNotNullOfOrNull { session ->
                val program = program(session) ?: return@firstNotNullOfOrNull null
                if (check.size < ObservationCycleEstimator.MIN_HELD_OUT) return@firstNotNullOfOrNull program.copy(confidence = UNCHECKED_CONFIDENCE)
                val agreement = ObservationCycleEstimator.agreement(check, program)
                if (agreement < ObservationCycleEstimator.MIN_HELD_OUT_ACCURACY) null else program.copy(confidence = agreement)
            }
    }

    /** Each of [startsEpochMs] on the ring of [cycleMs], relative to the first, in seconds (−cycle/2, cycle/2]. */
    private fun startDeviations(startsEpochMs: List<Long>, cycleMs: Long): List<Double> {
        val first = startsEpochMs.first().mod(cycleMs)
        return startsEpochMs.map { ((it.mod(cycleMs) - first + cycleMs / 2).mod(cycleMs) - cycleMs / 2) / 1000.0 }
    }

    /** Leaving direction for [turn] (right = clockwise, as in [LightEvent.turnOf]); null if either is unknown. */
    fun exitHeading(headingDeg: Int?, turn: LightEvent.Turn?): Int? {
        if (headingDeg == null || turn == null) return null
        val offset = when (turn) {
            LightEvent.Turn.STRAIGHT -> 0
            LightEvent.Turn.RIGHT -> 90
            LightEvent.Turn.LEFT -> -90
            LightEvent.Turn.U_TURN -> 180
        }
        return ((headingDeg + offset) % 360 + 360) % 360
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun spread(values: List<Double>): Double =
        if (values.size < 2) 0.0 else abs(values.max() - values.min())
}
