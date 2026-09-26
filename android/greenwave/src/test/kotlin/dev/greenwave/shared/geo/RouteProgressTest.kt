package dev.greenwave.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProgressTest {
    private val straightNorthPath = listOf(
        LatLng(52.5200, 13.4050),
        LatLng(52.5300, 13.4050),
    )

    @Test
    fun progressAtTheStartOfThePathIsZero() {
        val progress = distanceAlongPath(straightNorthPath, straightNorthPath.first())

        assertEquals(0.0, progress, absoluteTolerance = 0.001)
    }

    @Test
    fun progressAtTheEndOfThePathEqualsTheSegmentLength() {
        val progress = distanceAlongPath(straightNorthPath, straightNorthPath.last())

        // 0.01 degrees latitude, using the 111_320 m/degree local-flat
        // approximation that distanceAlongPath uses internally.
        assertTrue(abs(progress - 1113.2) < 1.0, "was $progress")
    }

    @Test
    fun aPositionOffToTheSideProjectsOntoTheNearestPointOnTheSegment() {
        // Same latitude as the segment's midpoint, but offset in longitude —
        // should project onto the segment, not measure the diagonal distance.
        val midLatitude = (straightNorthPath[0].latitude + straightNorthPath[1].latitude) / 2
        val offToTheSide = LatLng(midLatitude, 13.41)

        val progress = distanceAlongPath(straightNorthPath, offToTheSide)

        assertTrue(abs(progress - 556.6) < 5.0, "was $progress")
    }

    @Test
    fun distanceToPathForAPositionOffToTheSideIsThePerpendicularDistance() {
        val midLatitude = (straightNorthPath[0].latitude + straightNorthPath[1].latitude) / 2
        val offToTheSide = LatLng(midLatitude, 13.41)

        val distance = distanceToPath(straightNorthPath, offToTheSide)

        // Same geometry as the distanceAlongPath "off to the side" test: ~0.005
        // degrees longitude at this latitude, using the 111_320 m/degree
        // approximation and cos(52.52°) for the longitude scale factor.
        assertTrue(abs(distance - 338.7) < 5.0, "was $distance")
    }

    @Test
    fun distanceToPathIsZeroForAPositionExactlyOnTheSegment() {
        val onTheSegment = LatLng(52.5250, 13.4050)

        val distance = distanceToPath(straightNorthPath, onTheSegment)

        assertTrue(distance < 0.001, "was $distance")
    }

    @Test
    fun splitPathAtPositionsWithNoSplitPointsReturnsTheWholePathAsOneSegment() {
        val segments = splitPathAtPositions(straightNorthPath, emptyList())

        assertEquals(1, segments.size)
        assertEquals(straightNorthPath, segments.single())
    }

    @Test
    fun splitPathAtPositionsSplitsAtAPointInTheMiddleOfTheOnlySegment() {
        val midpoint = LatLng(52.5250, 13.4050) // exactly on the segment, halfway

        val segments = splitPathAtPositions(straightNorthPath, listOf(midpoint))

        assertEquals(2, segments.size)
        assertEquals(listOf(straightNorthPath[0], midpoint), segments[0])
        assertEquals(listOf(midpoint, straightNorthPath[1]), segments[1])
    }

    @Test
    fun splitPathAtPositionsWithMultipleVerticesKeepsOriginalVerticesBetweenSplits() {
        val threePointPath = listOf(
            LatLng(52.5200, 13.4050),
            LatLng(52.5250, 13.4050),
            LatLng(52.5300, 13.4050),
        )
        // A split point on the second segment (between the 2nd and 3rd vertex)
        val splitOnSecondSegment = LatLng(52.5275, 13.4050)

        val segments = splitPathAtPositions(threePointPath, listOf(splitOnSecondSegment))

        assertEquals(2, segments.size)
        // First segment keeps the original middle vertex, then the split point
        assertEquals(listOf(threePointPath[0], threePointPath[1], splitOnSecondSegment), segments[0])
        assertEquals(listOf(splitOnSecondSegment, threePointPath[2]), segments[1])
    }

    @Test
    fun splitPathAtPositionsWithMultipleSplitPointsProducesOneMoreSegmentThanSplitPoints() {
        val threePointPath = listOf(
            LatLng(52.5200, 13.4050),
            LatLng(52.5250, 13.4050),
            LatLng(52.5300, 13.4050),
        )
        val firstSplit = LatLng(52.5225, 13.4050) // on the first segment
        val secondSplit = LatLng(52.5275, 13.4050) // on the second segment

        val segments = splitPathAtPositions(threePointPath, listOf(firstSplit, secondSplit))

        assertEquals(3, segments.size)
    }

    @Test
    fun pointAtDistanceAlongPathReturnsTheStartForZeroOrNegativeDistance() {
        assertEquals(straightNorthPath[0], pointAtDistanceAlongPath(straightNorthPath, 0.0))
        assertEquals(straightNorthPath[0], pointAtDistanceAlongPath(straightNorthPath, -50.0))
    }

    @Test
    fun pointAtDistanceAlongPathInterpolatesWithinASegment() {
        // Full segment is ~1113.2m (see progressAtTheEndOfThePathEqualsTheSegmentLength);
        // halfway there should land close to the segment's geographic midpoint.
        val point = pointAtDistanceAlongPath(straightNorthPath, 556.6)

        assertTrue(abs(point.latitude - 52.5250) < 0.0005, "was ${point.latitude}")
        assertTrue(abs(point.longitude - 13.4050) < 0.0001, "was ${point.longitude}")
    }

    @Test
    fun pointAtDistanceAlongPathClampsToTheEndBeyondPathLength() {
        val point = pointAtDistanceAlongPath(straightNorthPath, 5000.0)

        assertEquals(straightNorthPath.last(), point)
    }

    @Test
    fun pointAtDistanceAlongPathAtAVertexReturnsThatVertex() {
        val threePointPath = listOf(
            LatLng(52.5200, 13.4050),
            LatLng(52.5250, 13.4050),
            LatLng(52.5300, 13.4050),
        )
        val firstSegmentLength = distanceAlongPath(threePointPath, threePointPath[1])

        val point = pointAtDistanceAlongPath(threePointPath, firstSegmentLength)

        assertTrue(abs(point.latitude - threePointPath[1].latitude) < 0.0001, "was ${point.latitude}")
    }
}
