package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareTrimTest {
    private val home = LatLng(50.9, 6.95)
    private fun north(m: Double) = LatLng(home.latitude + m / 111_320.0, home.longitude)
    private fun ev(at: LatLng, t: Long = 1_000) = LightEvent(at, t, LightEvent.Kind.PASS_GREEN, 0)

    /** A ride north from [fromM] to [toM] at 5 m/s, one fix every 5 s, starting at [t0]. */
    private fun ShareTrim.ride(fromM: Double, toM: Double, t0: Long): Long {
        val steps = (kotlin.math.abs(toM - fromM) / 25).toInt()
        for (i in 0..steps) onFix(north(fromM + (toM - fromM) * i / steps), t0 + i * 5L)
        return t0 + steps * 5L
    }

    @Test
    fun keepsOnlyEventsOutsideBothRadii() {
        val trim = ShareTrim(Random(1))
        val end = trim.ride(0.0, 3_000.0, 0)
        // Times as ridden at 5 m/s.
        trim.add(listOf(ev(north(300.0), 60), ev(north(1_000.0), 200), ev(north(2_000.0), 400), ev(north(2_700.0), 540)))
        // 300 m from start and 300 m from the end are always inside; 1 km and 2 km never.
        assertEquals(listOf(north(1_000.0), north(2_000.0)), trim.end().map { it.light })
        assertTrue(end > 540)
    }

    @Test
    fun radiusVariesBetweenRides() {
        val radii = (1..20).map { seed ->
            val trim = ShareTrim(Random(seed))
            trim.ride(0.0, 2_000.0, 0)
            // The smallest distance from the start that still is shared tells the radius.
            trim.add((400..600 step 5).map { ev(north(it.toDouble()), 150) })
            trim.end().minOf { it.light.latitude }
        }
        assertTrue(radii.distinct().size > 10)
    }

    @Test
    fun endStartsAfreshAndRideWithoutFixesSharesNothing() {
        val trim = ShareTrim(Random(2))
        trim.add(listOf(ev(north(1_000.0))))
        assertEquals(emptyList(), trim.end())
        trim.onFix(north(0.0), 0)
        trim.onFix(north(3_000.0), 600)
        assertEquals(emptyList(), trim.end())
    }

    @Test
    fun roundTripSharesNothingNearHome() {
        // Out and back: start and end are both at home, so lights near home stay private on both ends.
        val trim = ShareTrim(Random(3))
        val back = trim.ride(0.0, 3_000.0, 0)
        trim.ride(3_000.0, 100.0, back + 5)
        trim.add(listOf(ev(north(350.0), 300), ev(north(1_500.0), 300)))
        assertEquals(listOf(north(1_500.0)), trim.end().map { it.light })
    }

    @Test
    fun aLongStopMidRecordingCountsAsEndAndStart() {
        // Recording runs on: ride to a place 3 km away, stay 20 minutes, ride on.
        val trim = ShareTrim(Random(4))
        var t = trim.ride(0.0, 3_000.0, 0)
        repeat(240) { t += 5; trim.onFix(north(3_000.0 + (it % 3) * 20.0), t) } // GPS wanders a little
        t = trim.ride(3_000.0, 6_000.0, t + 5)
        trim.add(listOf(ev(north(1_500.0), 300), ev(north(2_700.0), 540), ev(north(3_300.0), 2_000), ev(north(4_500.0), 2_200)))
        assertEquals(listOf(north(1_500.0), north(4_500.0)), trim.end().map { it.light })
    }

    @Test
    fun waitingAtARedLightIsNoPause() {
        val trim = ShareTrim(Random(5))
        var t = trim.ride(0.0, 2_000.0, 0)
        repeat(30) { t += 5; trim.onFix(north(2_000.0), t) } // 150 s at the light
        trim.ride(2_000.0, 4_000.0, t + 5)
        trim.add(listOf(ev(north(2_000.0), 500)))
        assertEquals(listOf(north(2_000.0)), trim.end().map { it.light })
    }

    @Test
    fun aGapWithoutFixesCutsOnBothSides() {
        // App restarted or GPS lost for 10 minutes; the recording went on 1 km further.
        val trim = ShareTrim(Random(6))
        val t = trim.ride(0.0, 3_000.0, 0)
        trim.ride(4_000.0, 8_000.0, t + 600)
        trim.add(listOf(ev(north(1_500.0), 300), ev(north(2_800.0), 580), ev(north(4_300.0), t + 700), ev(north(6_000.0), t + 1_000)))
        assertEquals(listOf(north(1_500.0), north(6_000.0)), trim.end().map { it.light })
    }

    @Test
    fun eventsRightAfterALateFirstFixAreNotShared() {
        // Cold start: the first fix comes 700 m from home; a light passed 60 s later lies outside the radius but inside the edge time.
        val trim = ShareTrim(Random(7))
        trim.ride(700.0, 5_000.0, 0)
        trim.add(listOf(ev(north(1_400.0), 60), ev(north(2_500.0), 360)))
        assertEquals(listOf(north(2_500.0)), trim.end().map { it.light })
    }

    @Test
    fun aSingleGpsOutlierDoesNotHideAPause() {
        // Stay 8 minutes at a café 3 km out; one fix in the middle jumps 300 m away and back,
        // which splits the stay into two parts shorter than the 5-minute pause.
        val trim = ShareTrim(Random(6))
        var t = trim.ride(0.0, 3_000.0, 0)
        repeat(96) {
            t += 5
            trim.onFix(if (it == 48) north(3_300.0) else north(3_000.0 + (it % 3) * 20.0), t)
        }
        t = trim.ride(3_000.0, 6_000.0, t + 5)
        trim.add(listOf(ev(north(1_500.0), 300), ev(north(2_700.0), 540), ev(north(4_500.0), 1_500)))
        assertEquals(listOf(north(1_500.0), north(4_500.0)), trim.end().map { it.light })
    }

    @Test
    fun coldStartFixFarOffStillCutsAroundTheRealStart() {
        // The first fix lies 2 km off; 30 s later GPS settles at home and the rider sets off slowly (2 m/s).
        val trim = ShareTrim(Random(7))
        trim.onFix(north(-2_000.0), 0)
        var t = 30L
        var m = 0.0
        while (m <= 3_000.0) { trim.onFix(north(m), t); t += 5; m += 10.0 }
        // 380 m from home is passed after 3 min, outside the first 2 minutes; 1.5 km is far from all ends.
        trim.add(listOf(ev(north(380.0), 220), ev(north(1_500.0), 780)))
        assertEquals(listOf(north(1_500.0)), trim.end().map { it.light })
    }

    @Test
    fun fastRidingIsNoColdStartJump() {
        // 12 m/s downhill right after the start must not add cuts along the way.
        val trim = ShareTrim(Random(8))
        var t = 0L
        var m = 0.0
        while (m <= 5_000.0) { trim.onFix(north(m), t); t += 5; m += 60.0 }
        trim.add(listOf(ev(north(1_800.0), 150)))
        assertEquals(listOf(north(1_800.0)), trim.end().map { it.light })
    }
}
