package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovementResolverTest {
    private val light = LatLng(50.0, 7.0)

    /** [north]/[east] metres from the light. */
    private fun at(north: Double, east: Double) = LatLng(
        latitude = light.latitude + north / 111_320.0,
        longitude = light.longitude + east / (111_320.0 * cos(light.latitude * PI / 180.0)),
    )

    private fun fix(t: Long, north: Double, east: Double, kmh: Double = 18.0) =
        LocationUpdate(at(north, east), t, kmh, null)

    private fun stop(start: Long, end: Long, north: Double, east: Double = 0.0) =
        StopEvent(startEpochSec = start, endEpochSec = end, durationSec = end - start,
            position = at(north, east), minSpeedKmh = 0.0, maxSpeedKmh = 12.0)

    @Test
    fun passStraightNorthIsReleasedOncePastWithBothDirections() {
        val r = MovementResolver()
        // Riding north through the light at t=10.
        for (t in 0L..9L) assertTrue(r.onUpdate(fix(t, -100.0 + t * 10, 0.0)).isEmpty())
        r.addPass(LightEvent(light, 10, LightEvent.Kind.PASS_GREEN, null))
        assertTrue(r.onUpdate(fix(10, 0.0, 0.0)).isEmpty())
        assertTrue(r.onUpdate(fix(12, 20.0, 0.0)).isEmpty(), "not yet 40 m past")
        val out = r.onUpdate(fix(15, 50.0, 0.0))
        assertEquals(1, out.size)
        assertEquals(0, out[0].headingDeg)
        assertEquals(0, out[0].exitHeadingDeg)
        assertEquals(LightEvent.Turn.STRAIGHT, out[0].turn)
    }

    @Test
    fun stopThenTurnLeftIsLearnedAsLeftTurn() {
        val r = MovementResolver()
        for (t in 0L..5L) r.onUpdate(fix(t, -60.0 + t * 10, 0.0))
        r.onUpdate(fix(10, -5.0, 0.0, 0.0))
        r.addStop(stop(10, 40, -5.0), light)
        // Rolls off and turns west (left when heading north).
        r.onUpdate(fix(41, 0.0, 0.0))
        val out = r.onUpdate(fix(45, 0.0, -45.0))
        assertEquals(listOf(LightEvent.Kind.STOP_START, LightEvent.Kind.STOP_END), out.map { it.kind })
        assertTrue(out.all { it.turn == LightEvent.Turn.LEFT }, "turns: ${out.map { it.turn }}")
    }

    @Test
    fun creepingInAQueueIsOneWait() {
        val r = MovementResolver()
        for (t in 0L..5L) r.onUpdate(fix(t, -100.0 + t * 10, 0.0))
        // Stops 45 m before the light, creeps up, stops again at the light.
        r.addStop(stop(10, 50, -45.0), light)
        r.onUpdate(fix(52, -30.0, 0.0, 8.0))
        r.addStop(stop(55, 90, -5.0), light)
        r.onUpdate(fix(92, 0.0, 0.0))
        val out = r.onUpdate(fix(96, 45.0, 0.0))
        assertEquals(2, out.size, "one red start and one green start: $out")
        assertEquals(10L, out.single { it.kind == LightEvent.Kind.STOP_START }.epochSec)
        assertEquals(90L, out.single { it.kind == LightEvent.Kind.STOP_END }.epochSec)
    }

    @Test
    fun separateWaitsAtTheSameLightStaySeparate() {
        val r = MovementResolver()
        r.onUpdate(fix(0, -60.0, 0.0))
        r.addStop(stop(10, 40, -5.0), light)
        val first = r.onUpdate(fix(50, 50.0, 0.0))
        assertEquals(2, first.size)
        // Next day-ish: another stop at the same light is its own wait.
        r.onUpdate(fix(1_000, -60.0, 0.0))
        r.addStop(stop(1_010, 1_030, -5.0), light)
        assertEquals(2, r.onUpdate(fix(1_040, 50.0, 0.0)).size)
    }

    @Test
    fun unresolvedEventsAreReleasedWithoutExitAfterTimeout() {
        val r = MovementResolver(maxPendingSec = 60)
        r.onUpdate(fix(0, -60.0, 0.0))
        r.addStop(stop(10, 40, -5.0), light)
        // GPS stays at the light (ride ended there).
        assertTrue(r.onUpdate(fix(60, -3.0, 0.0, 0.0)).isEmpty())
        val out = r.onUpdate(fix(101, -3.0, 0.0, 0.0))
        assertEquals(2, out.size)
        assertTrue(out.all { it.exitHeadingDeg == null && it.turn == null })
    }

    @Test
    fun flushReleasesPending() {
        val r = MovementResolver()
        r.onUpdate(fix(0, -60.0, 0.0))
        r.addStop(stop(10, 40, -5.0), light)
        val out = r.flush()
        assertEquals(2, out.size)
        assertNull(out[0].exitHeadingDeg)
        assertEquals(0, out[0].headingDeg)
    }

    @Test
    fun turnClassification() {
        assertEquals(LightEvent.Turn.STRAIGHT, LightEvent.turnOf(350.0, 20.0))
        assertEquals(LightEvent.Turn.RIGHT, LightEvent.turnOf(0.0, 90.0))
        assertEquals(LightEvent.Turn.LEFT, LightEvent.turnOf(10.0, 280.0))
        assertEquals(LightEvent.Turn.U_TURN, LightEvent.turnOf(0.0, 180.0))
    }

    @Test
    fun oldEventsWithoutTurnMatchAnyMovement() {
        val old = LightEvent(light, 0, LightEvent.Kind.STOP_END, 0)
        assertTrue(old.sameMovement(0.0, LightEvent.Turn.LEFT))
        val left = old.copy(exitHeadingDeg = 270)
        assertTrue(left.sameMovement(0.0, LightEvent.Turn.LEFT))
        assertTrue(!left.sameMovement(0.0, LightEvent.Turn.STRAIGHT))
        assertTrue(left.sameMovement(0.0, null))
    }
}
