package dev.greenwave.shared.calibration

import dev.greenwave.shared.calibration.Calibration.Change.GREEN
import dev.greenwave.shared.calibration.Calibration.Change.RED
import dev.greenwave.shared.calibration.Calibration.Tap
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import dev.greenwave.shared.tracking.ObservationCycleEstimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationTest {
    private val light = LatLng(52.5317, 13.3846)

    // Fixed program: cycle 90 s, green from epoch phase 10 for 30 s.
    private val cycle = 90
    private val greenStart = 10
    private val green = 30
    private val start = 1_790_000_000L - 1_790_000_000L % 86_400 + 8 * 3600

    /** Taps of a careful rider over [changes] changes, starting with the next change to green. */
    private fun taps(changes: Int, jitterMs: (Int) -> Long = { 0 }): List<Tap> {
        val firstGreen = start + (greenStart - start).mod(cycle.toLong())
        return (0 until changes).map { i ->
            val epochSec = firstGreen + (i / 2) * cycle + if (i % 2 == 0) 0 else green
            Tap((epochSec - start) * 1000 + 300 + jitterMs(i), if (i % 2 == 0) GREEN else RED)
        }
    }

    private fun session(taps: List<Tap>, heading: Int? = 90, turn: LightEvent.Turn? = LightEvent.Turn.STRAIGHT) =
        Calibration.Session(light, heading, turn, start * 1000, taps)

    @Test
    fun rejectsDoubleTapsAndSlips() {
        val taps = listOf(Tap(10_000, GREEN))
        assertNotNull(Calibration.reject(taps, GREEN, 11_000))
        assertNotNull(Calibration.reject(taps, RED, 12_000))
        assertNull(Calibration.reject(taps, RED, 13_000))
        // The same change long after: a change in between was missed, taken (it leaves a gap).
        assertNull(Calibration.reject(taps, GREEN, 100_000))
        assertNull(Calibration.reject(emptyList(), RED, 0))
    }

    @Test
    fun summaryOfAFixedProgram() {
        val s = Calibration.summary(taps(7, jitterMs = { if (it % 2 == 0) 400L * (it % 3) else -300L }))
        assertEquals(3, s.cycles)
        assertTrue(abs(s.cycleSec!! - 90) < 1.0, "cycle ${s.cycleSec}")
        assertTrue(abs(s.greenSec!! - 30) < 1.5, "green ${s.greenSec}")
        assertTrue(abs(s.redSec!! - 60) < 1.5, "red ${s.redSec}")
        assertTrue(s.steady)
        // red²/(2·cycle) = 3600/180
        assertTrue(abs(s.expectedWaitSec!! - 20.0) < 1.0, "wait ${s.expectedWaitSec}")
        assertEquals(0, s.skipped)
    }

    @Test
    fun varyingCyclesAreNotSteady() {
        val s = Calibration.summary(taps(7, jitterMs = { if (it == 4) 12_000L else 0L }))
        assertEquals(3, s.cycles)
        assertFalse(s.steady)
    }

    @Test
    fun missedChangeLeavesAGapInsteadOfALongPhase() {
        // Green, red, (green missed), red, green: no phase across the two reds, no cycle either.
        val all = taps(7)
        val taps = listOf(all[0], all[1], all[3], all[4])
        val s = Calibration.summary(taps)
        assertEquals(0, s.cycles)
        assertEquals(1, s.skipped)
        assertEquals(listOf(true to 30.0, false to 60.0), Calibration.phases(taps).map { it.green to it.sec })
    }

    @Test
    fun overlongPhaseDoesNotCount() {
        val taps = listOf(Tap(0, GREEN), Tap(30_000, RED), Tap(400_000, GREEN))
        val s = Calibration.summary(taps)
        assertEquals(0, s.cycles)
        assertEquals(1, s.skipped)
        assertNull(s.redSec)
    }

    @Test
    fun threeSteadyCyclesGiveTheProgram() {
        val program = assertNotNull(Calibration.program(session(taps(7, jitterMs = { if (it % 2 == 0) 600L * (it % 3) else -400L }))))
        assertEquals(cycle, program.cycleLengthSec)
        // Late green taps and early red taps make the measured green up to a second shorter.
        assertTrue(abs(program.greenDurationSec - green) <= 1, "green ${program.greenDurationSec}")
        assertTrue(abs(program.greenStartOffsetSec - greenStart) <= 1, "start ${program.greenStartOffsetSec}")
        // The estimator could not tell 90 s from 45 s from these taps; the measurement can.
        assertNull(Calibration.program(session(taps(5))), "two cycles are too few")
        assertNull(Calibration.program(session(taps(7, jitterMs = { if (it == 4) 12_000L else 0L }))), "varying cycles")
    }

    @Test
    fun greenStartNearTheCycleEndIsNotSplit() {
        // Green starts at phase 0 of the epoch: taps fall just before and just after the ring's seam.
        val firstGreen = start + (0 - start).mod(cycle.toLong())
        val taps = (0 until 7).map { i ->
            val epochMs = (firstGreen + (i / 2) * cycle + if (i % 2 == 0) 0 else green) * 1000 + if (i % 4 == 0) -500L else 500L
            Tap(epochMs - start * 1000, if (i % 2 == 0) GREEN else RED)
        }
        val program = assertNotNull(Calibration.program(session(taps)))
        assertTrue(program.greenStartOffsetSec <= 1 || program.greenStartOffsetSec >= cycle - 1, "start ${program.greenStartOffsetSec}")
    }

    @Test
    fun countsAtTheSameTimeOfDayForFourWeeks() {
        val sessions = listOf(session(taps(7)))
        assertEquals(Calibration.UNCHECKED_CONFIDENCE, Calibration.learned(sessions, emptyList(), start + 3_600)?.confidence)
        assertNotNull(Calibration.learned(sessions, emptyList(), start + 27 * 86_400L))
        assertNull(Calibration.learned(sessions, emptyList(), start + 3 * 3_600), "other time of day")
        assertNull(Calibration.learned(sessions, emptyList(), start + 29 * 86_400L), "too old")
        assertNull(Calibration.learned(sessions, emptyList(), start - 60), "from the future")
    }

    @Test
    fun observationsCanContradictTheMeasurement() {
        val sessions = listOf(session(taps(7)))
        val now = start + 1_800
        fun stop(epochSec: Long, kind: LightEvent.Kind) = LightEvent(light, epochSec, kind, 90)
        // Green starts at phase 10 and red at phase 40, as measured.
        val fitting = (1..4).flatMap { d ->
            val t = start - d * 86_400L + (greenStart - (start - d * 86_400L)).mod(cycle.toLong())
            listOf(stop(t - 50, LightEvent.Kind.STOP_START), stop(t, LightEvent.Kind.STOP_END))
        }
        assertTrue(Calibration.learned(sessions, fitting, now)!!.confidence >= ObservationCycleEstimator.MIN_HELD_OUT_ACCURACY)
        // Green starts half a cycle off: another signal group or the wrong light.
        val off = fitting.map { it.copy(epochSec = it.epochSec + 45) }
        assertNull(Calibration.learned(sessions, off, now))
    }

    @Test
    fun matchesMovementLikeLightEvents() {
        val s = session(taps(3), heading = 90, turn = LightEvent.Turn.LEFT)
        assertTrue(s.matches(80.0, LightEvent.Turn.LEFT, null))
        assertTrue(s.matches(null, null, true))
        assertFalse(s.matches(270.0, LightEvent.Turn.LEFT, null))
        assertFalse(s.matches(90.0, LightEvent.Turn.STRAIGHT, null))
        assertEquals(0, Calibration.exitHeading(90, LightEvent.Turn.LEFT))
        assertEquals(80, Calibration.exitHeading(350, LightEvent.Turn.RIGHT))
        assertNull(Calibration.exitHeading(null, LightEvent.Turn.RIGHT))
    }
}
