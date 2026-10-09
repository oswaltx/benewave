package dev.greenwave.shared.navigation

import dev.greenwave.shared.geo.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManeuverDeriverTest {

    // Build paths in local metres (x = east, y = north) around a Berlin-ish
    // origin, so the expected turn angles are obvious from the coordinates.
    private val originLat = 52.5
    private val originLng = 13.4
    private fun p(x: Double, y: Double) = LatLng(
        latitude = originLat + y / 111_320.0,
        longitude = originLng + x / (111_320.0 * cos(originLat * PI / 180.0)),
    )

    private fun directions(path: List<LatLng>) = ManeuverDeriver.derive(path).map { it.direction }

    @Test
    fun rightAngleRightTurn() {
        val maneuvers = ManeuverDeriver.derive(listOf(p(0.0, 0.0), p(0.0, 200.0), p(200.0, 200.0)))

        assertEquals(listOf(TurnDirection.RIGHT, TurnDirection.ARRIVE), maneuvers.map { it.direction })
        val turn = maneuvers.first()
        assertTrue(abs(turn.turnAngleDegrees - 90.0) < 1.0, "angle ${turn.turnAngleDegrees}")
        assertTrue(turn.distanceAlongPathMeters in 195.0..205.0, "distance ${turn.distanceAlongPathMeters}")
    }

    @Test
    fun rightAngleLeftTurn() {
        val maneuvers = ManeuverDeriver.derive(listOf(p(0.0, 0.0), p(0.0, 200.0), p(-200.0, 200.0)))

        assertEquals(TurnDirection.LEFT, maneuvers.first().direction)
        assertTrue(abs(maneuvers.first().turnAngleDegrees + 90.0) < 1.0)
    }

    @Test
    fun fortyFiveDegreesIsSlight() {
        val path = listOf(p(0.0, 0.0), p(0.0, 200.0), p(141.0, 341.0))
        assertEquals(listOf(TurnDirection.SLIGHT_RIGHT, TurnDirection.ARRIVE), directions(path))
    }

    @Test
    fun smallWigglesAreNotManeuvers() {
        val path = listOf(p(0.0, 0.0), p(0.0, 100.0), p(3.0, 200.0), p(0.0, 300.0))
        assertEquals(listOf(TurnDirection.ARRIVE), directions(path))
    }

    @Test
    fun smoothCurveOfManyShortSegmentsIsOneTurn() {
        // North for 100 m, then a quarter circle (r = 10 m — a typical
        // street corner — drawn as 10 segments of 9° each) bending right,
        // then east. No single vertex turns more than 9°.
        val arc = (0..10).map { step ->
            val theta = (180.0 - step * 9.0) * PI / 180.0
            p(10.0 + 10.0 * cos(theta), 100.0 + 10.0 * sin(theta))
        }
        val path = listOf(p(0.0, 0.0)) + arc + listOf(p(110.0, 110.0))

        val maneuvers = ManeuverDeriver.derive(path)

        assertEquals(listOf(TurnDirection.RIGHT, TurnDirection.ARRIVE), maneuvers.map { it.direction })
    }

    @Test
    fun uTurnIsSharpAndCountedOnce() {
        val path = listOf(p(0.0, 0.0), p(0.0, 200.0), p(5.0, 200.0), p(5.0, 0.0))
        assertEquals(listOf(TurnDirection.SHARP_RIGHT, TurnDirection.ARRIVE), directions(path))
    }

    @Test
    fun rightThenLeftJogStaysTwoManeuvers() {
        val path = listOf(p(0.0, 0.0), p(0.0, 100.0), p(10.0, 100.0), p(10.0, 200.0))
        assertEquals(
            listOf(TurnDirection.SLIGHT_RIGHT, TurnDirection.SLIGHT_LEFT, TurnDirection.ARRIVE),
            directions(path),
        )
    }

    @Test
    fun degeneratePaths() {
        assertEquals(emptyList(), ManeuverDeriver.derive(listOf(p(0.0, 0.0))))
        assertEquals(listOf(TurnDirection.ARRIVE), directions(listOf(p(0.0, 0.0), p(0.0, 50.0))))
        // Duplicate vertices must not produce NaN angles or phantom turns.
        assertEquals(listOf(TurnDirection.ARRIVE), directions(listOf(p(0.0, 0.0), p(0.0, 50.0), p(0.0, 50.0), p(0.0, 100.0))))
    }

    @Test
    fun classifyBoundaries() {
        assertEquals(TurnDirection.STRAIGHT, ManeuverDeriver.classify(10.0))
        assertEquals(TurnDirection.SLIGHT_RIGHT, ManeuverDeriver.classify(30.0))
        assertEquals(TurnDirection.SLIGHT_LEFT, ManeuverDeriver.classify(-45.0))
        assertEquals(TurnDirection.RIGHT, ManeuverDeriver.classify(60.0))
        assertEquals(TurnDirection.LEFT, ManeuverDeriver.classify(-90.0))
        assertEquals(TurnDirection.SHARP_RIGHT, ManeuverDeriver.classify(170.0))
        assertEquals(TurnDirection.SHARP_LEFT, ManeuverDeriver.classify(-135.0))
    }

    @Test
    fun normalizeDegreesWrapsIntoHalfOpenRange() {
        assertEquals(-90.0, ManeuverDeriver.normalizeDegrees(270.0))
        assertEquals(90.0, ManeuverDeriver.normalizeDegrees(-270.0))
        assertEquals(180.0, ManeuverDeriver.normalizeDegrees(-180.0))
        assertEquals(180.0, ManeuverDeriver.normalizeDegrees(540.0))
    }

    @Test
    fun bearingPointsClockwiseFromNorth() {
        assertTrue(abs(ManeuverDeriver.bearingDegrees(p(0.0, 0.0), p(0.0, 100.0))) < 0.01)
        assertTrue(abs(ManeuverDeriver.bearingDegrees(p(0.0, 0.0), p(100.0, 0.0)) - 90.0) < 0.01)
        assertTrue(abs(abs(ManeuverDeriver.bearingDegrees(p(0.0, 0.0), p(0.0, -100.0))) - 180.0) < 0.01)
    }

    // ---- upcoming ----

    private val lPath = listOf(p(0.0, 0.0), p(0.0, 200.0), p(200.0, 200.0))

    @Test
    fun upcomingTurnWithinRangeShowsTheTurn() {
        val next = ManeuverDeriver.upcoming(ManeuverDeriver.derive(lPath), progressMeters = 50.0)!!
        assertEquals(TurnDirection.RIGHT, next.direction)
        assertTrue(next.distanceMeters in 145.0..155.0, "distance ${next.distanceMeters}")
    }

    @Test
    fun farTurnShowsStraightWithDistance() {
        val longLeg = listOf(p(0.0, 0.0), p(0.0, 1000.0), p(200.0, 1000.0))
        val next = ManeuverDeriver.upcoming(ManeuverDeriver.derive(longLeg), progressMeters = 0.0)!!
        assertEquals(TurnDirection.STRAIGHT, next.direction)
        assertTrue(next.distanceMeters in 990.0..1010.0)
    }

    @Test
    fun afterLastTurnShowsArrival() {
        val next = ManeuverDeriver.upcoming(ManeuverDeriver.derive(lPath), progressMeters = 250.0)!!
        assertEquals(TurnDirection.ARRIVE, next.direction)
        assertTrue(next.distanceMeters in 145.0..155.0)
    }

    @Test
    fun nothingAheadIsNull() {
        assertNull(ManeuverDeriver.upcoming(ManeuverDeriver.derive(lPath), progressMeters = 1000.0))
        assertNull(ManeuverDeriver.upcoming(emptyList(), progressMeters = 0.0))
    }
}
