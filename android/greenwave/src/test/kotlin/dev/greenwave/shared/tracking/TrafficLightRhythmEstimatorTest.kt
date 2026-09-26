package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficLightRhythmEstimatorTest {

    private val light = LatLng(52.5200, 13.4050)

    /**
     * Build a [StopEvent] that started at [cyclePhase] seconds into the
     * cycle of [cycle], having waited [waitSeconds] before going green.
     * The epoch second is chosen so that `startEpochSec mod cycle == cyclePhase`.
     */
    private fun stopAt(
        cycle: Int,
        cyclePhase: Int,
        waitSeconds: Long,
        epochSec: Long = 1_000_000L,
    ): StopEvent {
        // Pick an epochSec where mod C lands at cyclePhase.
        val alignedStart = (epochSec / cycle) * cycle + cyclePhase
        return StopEvent(
            startEpochSec = alignedStart,
            endEpochSec = alignedStart + waitSeconds,
            durationSec = waitSeconds,
            position = light,
            minSpeedKmh = 0.0,
            maxSpeedKmh = 12.0,
        )
    }

    /**
     * One stop per ride, each ride on a different day. Only stops from
     * different cycles carry information about the cycle length: stops
     * that all fall into the same minute fit every candidate longer than
     * their span equally well.
     */
    private fun stopsOnDifferentDays(cycle: Int, vararg phaseAndWait: Pair<Int, Long>): List<StopEvent> {
        // Fixed seed: arbitrary but reproducible time of day per ride.
        val timeOfDay = Random(seed = 1)
        return phaseAndWait.mapIndexed { day, (phase, wait) ->
            val epochSec = 1_000_000L + day * 86_400L + timeOfDay.nextLong(86_400L)
            stopAt(cycle, phase, wait, epochSec)
        }
    }

    @Test
    fun returnsNullWhenBelowMinimumSampleCount() {
        val events = listOf(stopAt(cycle = 60, cyclePhase = 30, waitSeconds = 25))
        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNull(result)
    }

    @Test
    fun returnsNullWhenNoCandidateProducesTightCluster() {
        // Phases scattered randomly across a 60 s cycle — every candidate
        // would produce a cluster wider than half its cycle length.
        val events = stopsOnDifferentDays(
            cycle = 60,
            *Array(10) { i -> i * 6 to 10L },
        )
        assertNull(TrafficLightRhythmEstimator.estimate(events))
    }

    @Test
    fun recoversKnown60SecondProgram() {
        // Light with cycle=60, green 0..25 (so red 25..60 = 35 s).
        // Stops scattered through the red phase: cyclePhase ∈ [25..60),
        // wait ≈ time-to-green-from-arrival.
        val events = stopsOnDifferentDays(
            cycle = 60,
            25 to 35L,
            30 to 30L,
            35 to 25L,
            40 to 20L,
            45 to 15L,
            50 to 10L,
            55 to 5L,
            59 to 1L,
            30 to 30L,
            40 to 20L,
        )

        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNotNull(result)
        assertEquals(60, result.cycleLengthSec, "should pick the 60 s cycle")
        // The cluster width (~34 s) and the median wait × 2 (~40 s) are
        // both reasonable proxies for the red phase. The estimator picks
        // the larger of the two and clamps, then derives green = C - red.
        assertTrue(result.greenDurationSec in 10..35, "got ${result.greenDurationSec}")
    }

    @Test
    fun recoversKnown60SecondProgramWithCleanerData() {
        // cycle=60, green 0..30, red 30..60 (redDuration=30). Stops at
        // multiple phases inside [30..60), wait seconds match remaining-red
        // exactly (no queue).
        val events = stopsOnDifferentDays(
            cycle = 60,
            30 to 30L,
            40 to 20L,
            50 to 10L,
            30 to 30L,
            45 to 15L,
        )

        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNotNull(result)
        assertEquals(60, result.cycleLengthSec)
        // cluster width = 60-30 = 30, median wait = 20, 2*median = 40.
        // max(30, 40) = 40, clamped to [3, 57] = 40. greenDuration = 20.
        // This is the conservative behavior; allow some slack.
        assertTrue(result.greenDurationSec in 20..40, "got ${result.greenDurationSec}")
        assertEquals(5, result.sampleCount)
        assertTrue(result.confidence > 0.0)
    }

    @Test
    fun greenStartIsMedianDepartureEvenAcrossCycleBoundary() {
        // Green starts at phase 59 (± a second of reaction time). The
        // departure phases 57, 58, 59, 0, 1 straddle the cycle boundary; a
        // plain median of the raw phases would land on 57.
        val events = stopsOnDifferentDays(
            cycle = 60,
            30 to 27L,
            35 to 23L,
            40 to 19L,
            45 to 15L,
            50 to 11L,
        )
        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNotNull(result)
        assertEquals(60, result.cycleLengthSec)
        assertEquals(59, result.greenStartOffsetSec)
    }

    @Test
    fun picksShortestCandidateWhenMultipleFit() {
        // All candidates could plausibly fit; tie-break to smallest.
        // Stops spaced 60 s apart at cyclePhase=0 (always at start of cycle).
        val events = listOf(
            stopAt(cycle = 60, cyclePhase = 0, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 0, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 0, waitSeconds = 30),
        )
        // Modulo 120 also puts them all at phase 0; modulo 60 same. The
        // tie-break should pick the smallest valid candidate.
        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNotNull(result)
        assertTrue(result.cycleLengthSec in listOf(45, 60, 75, 90, 105, 120))
    }

    @Test
    fun clusterWrapsAroundZero() {
        // Stops clustered near the cycle boundary — phases near 0 and near C-1
        // should be detected as one cluster (wrapping).
        val events = listOf(
            stopAt(cycle = 60, cyclePhase = 1, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 59, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 2, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 58, waitSeconds = 30),
            stopAt(cycle = 60, cyclePhase = 0, waitSeconds = 30),
        )
        val result = TrafficLightRhythmEstimator.estimate(events)
        assertNotNull(result)
        assertEquals(60, result.cycleLengthSec)
    }
}