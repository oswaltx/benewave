package dev.greenwave.shared.calibration

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import dev.greenwave.shared.tracking.ObservationCycleEstimator
import dev.greenwave.shared.tracking.RhythmEstimate
import kotlin.math.abs
import kotlin.math.floor
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
 * learned programs it only counts at the same time of day, and only while the rider's own
 * observations and other measurements there don't contradict it ([learned]). A measurement
 * nothing has confirmed yet counts for a week, a confirmed one for four.
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

    /**
     * Steady cycles before the measured program is used for advice: with fewer, the cycle
     * can't be told from one half a second longer, which drifts by 20 s an hour.
     */
    const val MIN_PROGRAM_CYCLES = 5

    /** The cycle fitted over all green starts may be this far from a whole second, else it is not one. */
    const val MAX_CYCLE_ERROR_SEC = 0.25

    /** Each green start may deviate this much from the fitted integer cycle. */
    const val MAX_START_DEVIATION_SEC = 1.5

    /**
     * Taps come late; the change to red is taken this much earlier, so the measured green
     * ends rather too soon than too late (the green start is late anyway, the safe side).
     */
    const val RED_TAP_DELAY_SEC = 0.5

    /** Confidence of a measured program nothing could check yet; it counts for [UNCHECKED_MAX_AGE_SEC] only. */
    const val UNCHECKED_CONFIDENCE = 0.7
    const val UNCHECKED_MAX_AGE_SEC = 7 * 86_400L

    private const val GREEN_EVENT_SHIFT_SEC = 2L

    /** Another measurement confirms one only if made on another day. */
    private const val OTHER_DAY_SEC = 12 * 3_600L

    enum class Change { GREEN, RED }

    /** One tap, [atMs] after the session start (monotonic). */
    data class Tap(val atMs: Long, val change: Change)

    data class Session(
        val light: LatLng,
        /** Direction of approach (0 = north), null if unknown: then it is shown but never used for advice. */
        val headingDeg: Int?,
        /** Movement the measured signal is for, null if unknown (shown only, like [headingDeg]). */
        val turn: LightEvent.Turn?,
        val startEpochMs: Long,
        val taps: List<Tap>,
        /** Measured from a cycleway alongside the signal's way (as [LightEvent.sideway]), null if unknown. */
        val sideway: Boolean? = null,
    ) {
        /** Only a measurement for one known approach, turn and side may steer the speed (see [learned]). */
        val directed: Boolean get() = headingDeg != null && turn != null && sideway != null

        fun epochMs(tap: Tap): Long = startEpochMs + tap.atMs

        /** Measured for this approach and turn, seen from this kind of way; as [LightEvent.sameMovement] and [LightEvent.sameSide]. */
        fun matches(approachDeg: Double?, turn: LightEvent.Turn?, sideway: Boolean?): Boolean =
            LightEvent(light, startEpochMs / 1000, LightEvent.Kind.STOP_END, headingDeg, exitHeading(headingDeg, this.turn), this.sideway)
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
     * The program measured in [session]. The cycle is the slope of a line through all green
     * starts (numbered by cycle, so a missed change leaves a gap but no error); it must be
     * within [MAX_CYCLE_ERROR_SEC] of a whole second and every green start within
     * [MAX_START_DEVIATION_SEC] of that line. Null unless [MIN_PROGRAM_CYCLES] steady cycles
     * were measured.
     */
    fun program(session: Session): RhythmEstimate? {
        val s = summary(session.taps)
        if (s.cycles < MIN_PROGRAM_CYCLES || !s.steady) return null
        val approxMs = s.cycleSec!! * 1000
        // Green taps next to a plausible red phase; one without is a stray tap after a missed change.
        val taps = session.taps
        fun plausible(a: Tap, b: Tap) = a.change != b.change && (b.atMs - a.atMs) / 1000.0 <= MAX_PHASE_SEC
        val starts = taps.indices
            .filter { i ->
                taps[i].change == Change.GREEN &&
                    ((i > 0 && plausible(taps[i - 1], taps[i])) || (i < taps.lastIndex && plausible(taps[i], taps[i + 1])))
            }
            .map { session.epochMs(taps[it]) }
        val t0 = starts.first()
        val ks = starts.map { ((it - t0) / approxMs).roundToInt() }
        if (ks.last() < MIN_PROGRAM_CYCLES) return null
        val kMean = ks.average()
        val tMean = starts.map { (it - t0).toDouble() }.average()
        val slopeSec = ks.indices.sumOf { (ks[it] - kMean) * (starts[it] - t0 - tMean) } /
            ks.sumOf { (it - kMean) * (it - kMean) } / 1000
        val cycle = slopeSec.roundToInt()
        if (abs(slopeSec - cycle) > MAX_CYCLE_ERROR_SEC) return null
        val cycleMs = cycle * 1000L
        // Every green start moved back to the first cycle: all the same moment if the cycle is right.
        val firstCycle = starts.indices.map { (starts[it] - ks[it] * cycleMs).toDouble() }
        val startMs = median(firstCycle)!!
        if (firstCycle.any { abs(it - startMs) > MAX_START_DEVIATION_SEC * 1000 }) return null
        val green = floor(s.greenSec!! - RED_TAP_DELAY_SEC).toInt()
        if (green < ObservationCycleEstimator.MIN_GREEN_SEC || green > cycle - ObservationCycleEstimator.MIN_GREEN_SEC) return null
        return RhythmEstimate(
            cycleLengthSec = cycle,
            greenStartOffsetSec = (startMs.roundToLong().mod(cycleMs) / 1000.0).roundToInt() % cycle,
            greenDurationSec = green,
            sampleCount = session.taps.size,
            confidence = 1.0,
        )
    }

    /**
     * The newest measured program among [sessions] that counts at [nowEpochSec] and that
     * nothing contradicts; null if none. Only [Session.directed] measurements count. The
     * check uses the rider's [observed] events and the taps of measurements on other days:
     * with at least [ObservationCycleEstimator.MIN_HELD_OUT] of them the program must
     * explain them as well as a learned one its held-out events and counts for four weeks
     * (same time of day, as in [ObservationCycleEstimator]); with fewer, a week.
     * [sessions] must already be those for the light and movement asked about.
     */
    fun learned(sessions: List<Session>, observed: List<LightEvent>, nowEpochSec: Long): RhythmEstimate? {
        val directed = sessions.filter { it.directed }
        return directed
            .filter { ObservationCycleEstimator.inWindow(it.startEpochMs / 1000, nowEpochSec) }
            .sortedByDescending { it.startEpochMs }
            .firstNotNullOfOrNull { session ->
                val program = program(session) ?: return@firstNotNullOfOrNull null
                val otherDays = directed
                    .filter { abs(it.startEpochMs - session.startEpochMs) / 1000 >= OTHER_DAY_SEC }
                    .flatMap { events(it) }
                val check = ObservationCycleEstimator.relevant(observed + otherDays, nowEpochSec)
                when {
                    check.size >= ObservationCycleEstimator.MIN_HELD_OUT -> {
                        val agreement = ObservationCycleEstimator.agreement(check, program)
                        if (agreement < ObservationCycleEstimator.MIN_HELD_OUT_ACCURACY) null else program.copy(confidence = agreement)
                    }
                    nowEpochSec - session.startEpochMs / 1000 <= UNCHECKED_MAX_AGE_SEC -> program.copy(confidence = UNCHECKED_CONFIDENCE)
                    else -> null
                }
            }
    }

    /**
     * Taps of [session] as observations to check another measurement against: a change to
     * green is a green start, a change to red the start of red. Only taps that bound a
     * plausible phase.
     */
    private fun events(session: Session): List<LightEvent> {
        val taps = session.taps
        val used = BooleanArray(taps.size)
        taps.zipWithNext().forEachIndexed { i, (a, b) ->
            if (a.change != b.change && (b.atMs - a.atMs) / 1000.0 <= MAX_PHASE_SEC) {
                used[i] = true
                used[i + 1] = true
            }
        }
        val exit = exitHeading(session.headingDeg, session.turn)
        return taps.filterIndexed { i, _ -> used[i] }.map { tap ->
            LightEvent(
                light = session.light,
                // A green start as event counts only up to 4 s after the program's start and never
                // before it; two people's taps differ by a second or so, so move it 2 s later.
                epochSec = session.epochMs(tap) / 1000 + if (tap.change == Change.GREEN) GREEN_EVENT_SHIFT_SEC else 0,
                kind = if (tap.change == Change.GREEN) LightEvent.Kind.STOP_END else LightEvent.Kind.STOP_START,
                headingDeg = session.headingDeg,
                exitHeadingDeg = exit,
                sideway = session.sideway,
            )
        }
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
