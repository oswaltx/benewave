package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideGuardTest {
    private val origin = LatLng(50.9, 6.95)

    /** [north] metres north of the origin. */
    private fun fix(t: Long, north: Double, kmh: Double) =
        LocationUpdate(LatLng(origin.latitude + north / 111_320.0, origin.longitude), t, kmh, null)

    @Test
    fun ridingLearnsWithFastGps() {
        val g = RideGuard()
        var d = g.onFix(fix(0, 0.0, 20.0))
        for (t in 1L..60L) d = g.onFix(fix(t, t * 5.5, 20.0))
        assertEquals(RideGuard.State.RIDING, d.state)
        assertTrue(d.learn)
        assertEquals(RideGuard.Gps.FAST, d.gps)
    }

    @Test
    fun tramSpeedStopsLearningUntilCooldownPassed() {
        val g = RideGuard()
        for (t in 0L..30L) g.onFix(fix(t, t * 5.0, 20.0))
        var d = g.onFix(fix(31, 160.0, 50.0))
        for (t in 32L..45L) d = g.onFix(fix(t, 160.0 + (t - 31) * 14, 50.0))
        assertEquals(RideGuard.State.VEHICLE, d.state)
        assertTrue(!d.learn)
        // Slow again (tram stop / got off): still no learning for the cooldown.
        d = g.onFix(fix(80, 400.0, 15.0))
        assertTrue(!d.learn)
        d = g.onFix(fix(110, 500.0, 15.0))
        assertTrue(d.learn, "60 s after the last vehicle stretch")
    }

    @Test
    fun aFewFastSecondsDownhillAreNoVehicle() {
        val g = RideGuard()
        var d = g.onFix(fix(0, 0.0, 30.0))
        for (t in 1L..20L) d = g.onFix(fix(t, t * 10.0, if (t % 4 == 0L) 42.0 else 30.0))
        assertEquals(RideGuard.State.RIDING, d.state)
    }

    @Test
    fun redLightIsNotStandingStill() {
        val g = RideGuard()
        for (t in 0L..10L) g.onFix(fix(t, t * 5.0, 18.0))
        var d = g.onFix(fix(11, 55.0, 0.0))
        for (t in 12L..130L) d = g.onFix(fix(t, 55.0, 0.0))
        assertEquals(RideGuard.State.RIDING, d.state)
        assertTrue(d.learn)
    }

    @Test
    fun longStandstillSwitchesGpsOffAndEventuallyEnds() {
        val g = RideGuard()
        g.onFix(fix(0, 0.0, 15.0))
        var d = g.onFix(fix(1, 5.0, 0.0))
        for (t in 2L..310L) d = g.onFix(fix(t, 5.0 + (t % 3), 0.5))
        assertEquals(RideGuard.State.STILL, d.state)
        assertEquals(RideGuard.Gps.WAIT_FOR_MOTION, d.gps)
        assertTrue(!d.end)
        // GPS off now: only ticks.
        d = g.onTick(900)
        assertEquals(RideGuard.State.STILL, d.state)
        d = g.onTick(1_205)
        assertTrue(d.end)
    }

    @Test
    fun motionWakesGpsFast() {
        val g = RideGuard()
        g.onFix(fix(0, 0.0, 0.0))
        for (t in 1L..305L) g.onFix(fix(t, 0.0, 0.0))
        g.onMotion(400)
        val d = g.onTick(401)
        assertEquals(RideGuard.Gps.FAST, d.gps)
        assertTrue(!d.end)
    }

    @Test
    fun noFixIndoorsSlowsGpsThenEnds() {
        val g = RideGuard()
        g.onFix(fix(0, 0.0, 15.0))
        g.onFix(fix(1, 5.0, 15.0))
        assertEquals(RideGuard.Gps.FAST, g.onTick(60).gps)
        val slow = g.onTick(130)
        assertEquals(RideGuard.State.NO_FIX, slow.state)
        assertEquals(RideGuard.Gps.SLOW, slow.gps)
        assertTrue(!slow.learn)
        assertTrue(g.onTick(1_202).end)
        // A fix again: back to riding.
        assertEquals(RideGuard.State.RIDING, g.onFix(fix(1_210, 40.0, 15.0)).state)
    }
}
