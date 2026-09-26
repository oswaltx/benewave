package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopDetectorTest {

    private val stopPosition = LatLng(52.5200, 13.4050)

    /** Build a [LocationUpdate] with sensible defaults: standing still at [stopPosition]. */
    private fun stillAt(epochSec: Long, speedKmh: Double = 0.0): LocationUpdate =
        LocationUpdate(
            position = stopPosition,
            epochSec = epochSec,
            speedKmh = speedKmh,
            bearingDegrees = null,
        )

    /** Build a [LocationUpdate] for a rider cruising at [speedKmh]. */
    private fun movingAt(epochSec: Long, speedKmh: Double = 20.0): LocationUpdate =
        LocationUpdate(
            position = stopPosition,
            epochSec = epochSec,
            speedKmh = speedKmh,
            bearingDegrees = 90f,
        )

    @Test
    fun noEventWhenContinuouslyMoving() {
        val detector = StopDetector()
        val events = (0L..30L).map { detector.onUpdate(movingAt(it)) }
        assertTrue(events.all { it == null }, "expected no events, got $events")
    }

    @Test
    fun detectsStopWhenRiderSlowsForAtLeastMinDurationThenResumes() {
        val detector = StopDetector()
        // 5s of riding, 10s of standing (default minStopDurationSec=5),
        // 5s of riding again. Expect one event.
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        for (t in 5L..14L) events += detector.onUpdate(stillAt(t))
        for (t in 15L..19L) events += detector.onUpdate(movingAt(t))

        val real = events.filterNotNull()
        assertEquals(1, real.size, "expected exactly one stop event")
        val event = real.first()
        assertEquals(5L, event.startEpochSec)
        assertEquals(15L, event.endEpochSec)
        assertEquals(10L, event.durationSec)
    }

    @Test
    fun ignoresShortSlowdownsUnderMinDuration() {
        val detector = StopDetector()
        // 2s of standing — shorter than minStopDurationSec=5.
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        for (t in 5L..6L) events += detector.onUpdate(stillAt(t))
        for (t in 7L..11L) events += detector.onUpdate(movingAt(t))

        assertTrue(events.filterNotNull().isEmpty(), "expected no events for short slowdown")
    }

    @Test
    fun dropsStopsLongerThanMaxDuration() {
        val detector = StopDetector()
        // 400s of standing — longer than maxStopDurationSec=300.
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        for (t in 5L..404L) events += detector.onUpdate(stillAt(t))
        for (t in 405L..409L) events += detector.onUpdate(movingAt(t))

        assertTrue(events.filterNotNull().isEmpty(), "expected no events for overlong stop")
    }

    @Test
    fun detectsMultipleStopsBackToBack() {
        val detector = StopDetector()
        // Two stops with a short cruise between them.
        val events = mutableListOf<StopEvent?>()
        // first cruise
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        // first stop (10s)
        for (t in 5L..14L) events += detector.onUpdate(stillAt(t))
        // second cruise (10s, > windowSize, so detector fully resets to Moving)
        for (t in 15L..24L) events += detector.onUpdate(movingAt(t))
        // second stop (10s)
        for (t in 25L..34L) events += detector.onUpdate(stillAt(t))
        // third cruise
        for (t in 35L..39L) events += detector.onUpdate(movingAt(t))

        val real = events.filterNotNull()
        assertEquals(2, real.size, "expected exactly two stop events")
        assertEquals(5L, real[0].startEpochSec)
        assertEquals(25L, real[1].startEpochSec)
    }

    @Test
    fun jitterySampleDuringStopDoesNotPrematurelyEndTheStop() {
        val detector = StopDetector()
        // Standing for 10s, but one mid-window spike to 6 km/h (above the
        // 5 km/h resume threshold but only briefly). The 5-sample moving
        // average should stay below the resume threshold, so the stop holds.
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        events += detector.onUpdate(stillAt(5L))
        events += detector.onUpdate(stillAt(6L))
        events += detector.onUpdate(stillAt(7L, speedKmh = 6.0))  // jitter
        events += detector.onUpdate(stillAt(8L))
        events += detector.onUpdate(stillAt(9L))
        // Still standing, no resume yet.
        assertTrue(events.filterNotNull().isEmpty(), "expected no event during jittery stop")
    }

    @Test
    fun eventPositionIsLastKnownPositionWhileStopped() {
        val detector = StopDetector()
        val firstStopPos = LatLng(52.5100, 13.4000)
        val secondStopPos = LatLng(52.5100, 13.4010)  // drifted ~7m east

        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        for (t in 5L..9L) events += detector.onUpdate(stillAt(t).copy(position = firstStopPos))
        for (t in 10L..14L) events += detector.onUpdate(stillAt(t).copy(position = secondStopPos))
        for (t in 15L..19L) events += detector.onUpdate(movingAt(t))

        val event = events.filterNotNull().singleOrNull()
        assertNotNull(event)
        // The last known position while stopped was the drifted one.
        assertEquals(13.4010, event.position.longitude)
    }

    @Test
    fun minSpeedDuringStopReflectsGpsJitterIfPresent() {
        val detector = StopDetector()
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        events += detector.onUpdate(stillAt(5L, speedKmh = 0.0))
        events += detector.onUpdate(stillAt(6L, speedKmh = 0.4))  // tiny jitter
        events += detector.onUpdate(stillAt(7L, speedKmh = 0.0))
        events += detector.onUpdate(stillAt(8L, speedKmh = 0.0))
        events += detector.onUpdate(stillAt(9L, speedKmh = 0.0))
        for (t in 10L..14L) events += detector.onUpdate(movingAt(t))

        val event = events.filterNotNull().singleOrNull()
        assertNotNull(event)
        // minSpeed shouldn't be Infinity even though the rider never truly
        // moved — GPS reported values, even zero, are real observations.
        assertTrue(event.minSpeedKmh.isFinite())
        assertTrue(event.minSpeedKmh >= 0.0)
    }

    @Test
    fun customConfigShortensMinStopDuration() {
        val detector = StopDetector(
            StopDetectorConfig(minStopDurationSec = 2, maxStopDurationSec = 10),
        )
        // 3s stop is enough with min=2.
        val events = mutableListOf<StopEvent?>()
        for (t in 0L..4L) events += detector.onUpdate(movingAt(t))
        for (t in 5L..7L) events += detector.onUpdate(stillAt(t))
        for (t in 8L..12L) events += detector.onUpdate(movingAt(t))

        assertEquals(1, events.filterNotNull().size)
    }

    @Test
    fun noEventWhenSpeedStaysAtZeroAndTimeStandsStill() {
        // Edge case: a stopped rider with no time advancing — should not
        // emit anything and should not throw.
        val detector = StopDetector()
        val events = (0L..30L).map { detector.onUpdate(stillAt(0L)) }
        assertTrue(events.all { it == null })
        // Sanity: subsequent motion still gets detected.
        val resume = detector.onUpdate(movingAt(60L, speedKmh = 20.0))
        // We can't form a StopEvent because we never transitioned through
        // Moving first — so no event is the correct answer here.
        assertNull(resume)
    }
}
