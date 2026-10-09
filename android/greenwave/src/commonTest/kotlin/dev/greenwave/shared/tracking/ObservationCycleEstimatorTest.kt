package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservationCycleEstimatorTest {
    private val light = LatLng(50.94, 6.96)

    // Fixed program: cycle 90 s, green from phase 10 for 30 s.
    private val cycle = 90
    private val greenStart = 10
    private val green = 30

    /** One ride per day around 8:00 UTC for [days] days; a stop or a pass-through each time. */
    private fun rides(days: Int, seed: Int = 1): Pair<List<LightEvent>, Long> {
        val random = Random(seed)
        val events = mutableListOf<LightEvent>()
        val day0 = 1_790_000_000L - 1_790_000_000L % 86_400 + 8 * 3600
        for (d in 0 until days) {
            val arrival = day0 + d * 86_400L + random.nextInt(0, 600)
            val phase = (arrival - greenStart).mod(cycle.toLong())
            if (phase < green) {
                events.add(LightEvent(light, arrival, LightEvent.Kind.PASS_GREEN, 0))
            } else {
                val nextGreen = arrival + (cycle - phase)
                events.add(LightEvent(light, arrival, LightEvent.Kind.STOP_START, 0))
                events.add(LightEvent(light, nextGreen + random.nextInt(0, 3), LightEvent.Kind.STOP_END, 0))
            }
        }
        return events to day0 + days * 86_400L
    }

    @Test
    fun learnsCycleAndGreenWindowFromMixedEvents() {
        val (events, now) = rides(days = 25)
        val estimate = assertNotNull(ObservationCycleEstimator.estimate(events, now))
        assertEquals(90, estimate.cycleLengthSec)
        val startError = (estimate.greenStartOffsetSec - greenStart).mod(cycle)
        assertTrue(startError <= 4 || startError >= cycle - 1, "start ${estimate.greenStartOffsetSec}")
        // The learned window must lie inside the real green (safe side).
        assertTrue(startError + estimate.greenDurationSec <= green + 1, "green ${estimate.greenDurationSec}, start error $startError")
    }

    @Test
    fun randomEventsGiveNull() {
        val day0 = 1_790_000_000L - 1_790_000_000L % 86_400 + 8 * 3600
        val falsePositives = (1..20).count { seed ->
            val random = Random(seed)
            val events = (0 until 30).map { d ->
                val t = day0 + d * 86_400L + random.nextInt(0, 600)
                LightEvent(light, t, LightEvent.Kind.entries[random.nextInt(3)], 0)
            }
            ObservationCycleEstimator.estimate(events, day0 + 30 * 86_400L) != null
        }
        assertEquals(0, falsePositives)
    }

    @Test
    fun learnsAcrossSeeds() {
        val learned = (1..10).count { seed ->
            val (events, now) = rides(days = 25, seed = seed)
            val e = ObservationCycleEstimator.estimate(events, now)
            e != null && e.cycleLengthSec == 90
        }
        assertTrue(learned >= 8, "learned only $learned of 10")
    }

    @Test
    fun eventsFromOtherTimesOfDayAreIgnored() {
        val (events, now) = rides(days = 25)
        // Ask at 20:00 instead of 8:00: nothing relevant.
        assertNull(ObservationCycleEstimator.estimate(events, now + 12 * 3600))
    }

    @Test
    fun tooFewEventsGiveNull() {
        val (events, now) = rides(days = 3)
        assertNull(ObservationCycleEstimator.estimate(events, now))
    }
}

class PassDetectorTest {
    private val light = LatLng(50.9400, 6.9600)

    private fun fix(t: Long, lat: Double, speed: Double) =
        LocationUpdate(LatLng(lat, 6.9600), t, speed, 0f)

    @Test
    fun ridingThroughGivesOnePassAtTheClosestFix() {
        val detector = PassDetector()
        val events = mutableListOf<LightEvent>()
        // North-bound at ~5 m/s from 40 m south to 40 m north of the light.
        for (i in 0..16) {
            val lat = 50.9400 - 0.00036 + i * 0.000045
            events += detector.onUpdate(fix(1000L + i, lat, 18.0), listOf(light))
        }
        assertEquals(1, events.size)
        assertEquals(LightEvent.Kind.PASS_GREEN, events[0].kind)
        assertEquals(1008L, events[0].epochSec)
        assertEquals(0, events[0].headingDeg)
    }

    @Test
    fun slowingDownToAStopGivesNoPass() {
        val detector = PassDetector()
        val events = mutableListOf<LightEvent>()
        for (i in 0..16) {
            val lat = 50.9400 - 0.00036 + i * 0.000045
            val speed = if (i in 7..9) 2.0 else 18.0
            events += detector.onUpdate(fix(1000L + i, lat, speed), listOf(light))
        }
        assertTrue(events.isEmpty())
    }
}
