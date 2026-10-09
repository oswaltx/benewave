package dev.greenwave.shared.geo

import kotlin.math.cos
import kotlin.math.sqrt

/** Meters per degree of latitude/longitude, used for a local flat-earth
 * approximation. Accurate enough for projecting a GPS fix onto a bike
 * route's polyline, which only ever spans a few kilometers. */
private const val METERS_PER_DEGREE = 111_320.0

/**
 * How far along [path] (in meters, measured from `path.first()`) the point
 * closest to [position] is. Used to compare the rider's progress against
 * each traffic light's progress along the same route: a light is still
 * ahead if its distanceAlongPath is greater than the rider's.
 */
fun distanceAlongPath(path: List<LatLng>, position: LatLng): Double {
    require(path.size >= 2) { "Path needs at least two points" }

    var cumulativeDistance = 0.0
    var bestDistanceAlong = 0.0
    var bestDistanceToPath = Double.MAX_VALUE

    for (i in 0 until path.size - 1) {
        val segmentStart = path[i]
        val segmentEnd = path[i + 1]

        val projection = projectOntoSegment(segmentStart, segmentEnd, position)
        if (projection.distanceToSegment < bestDistanceToPath) {
            bestDistanceToPath = projection.distanceToSegment
            bestDistanceAlong = cumulativeDistance + projection.distanceAlongSegment
        }

        cumulativeDistance += distanceMeters(segmentStart, segmentEnd)
    }

    return bestDistanceAlong
}

/**
 * Perpendicular distance, in meters, from [position] to the closest point on
 * [path]. Used to check whether a point (e.g. an OSM traffic-light node) is
 * actually on this route, as opposed to merely inside its bounding box.
 */
fun distanceToPath(path: List<LatLng>, position: LatLng): Double {
    require(path.size >= 2) { "Path needs at least two points" }

    var bestDistanceToPath = Double.MAX_VALUE

    for (i in 0 until path.size - 1) {
        val projection = projectOntoSegment(path[i], path[i + 1], position)
        if (projection.distanceToSegment < bestDistanceToPath) {
            bestDistanceToPath = projection.distanceToSegment
        }
    }

    return bestDistanceToPath
}

private class SegmentProjection(val distanceToSegment: Double, val distanceAlongSegment: Double, val t: Double)

private fun projectOntoSegment(start: LatLng, end: LatLng, position: LatLng): SegmentProjection {
    // Flatten to local meters around `start` — valid for the short segments
    // (tens to hundreds of meters) a bike route is made of.
    val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(toRadians(start.latitude))

    val endX = (end.longitude - start.longitude) * metersPerDegreeLongitude
    val endY = (end.latitude - start.latitude) * METERS_PER_DEGREE
    val posX = (position.longitude - start.longitude) * metersPerDegreeLongitude
    val posY = (position.latitude - start.latitude) * METERS_PER_DEGREE

    val segmentLengthSquared = endX * endX + endY * endY
    val t = if (segmentLengthSquared == 0.0) {
        0.0
    } else {
        ((posX * endX + posY * endY) / segmentLengthSquared).coerceIn(0.0, 1.0)
    }

    val projX = t * endX
    val projY = t * endY

    val distanceToSegment = sqrt((posX - projX) * (posX - projX) + (posY - projY) * (posY - projY))
    val distanceAlongSegment = t * sqrt(segmentLengthSquared)

    return SegmentProjection(distanceToSegment, distanceAlongSegment, t)
}

private class PathLocation(val segmentIndex: Int, val point: LatLng)

private fun locateOnPath(path: List<LatLng>, position: LatLng): PathLocation {
    var bestSegmentIndex = 0
    var bestDistanceToPath = Double.MAX_VALUE
    var bestT = 0.0

    for (i in 0 until path.size - 1) {
        val projection = projectOntoSegment(path[i], path[i + 1], position)
        if (projection.distanceToSegment < bestDistanceToPath) {
            bestDistanceToPath = projection.distanceToSegment
            bestSegmentIndex = i
            bestT = projection.t
        }
    }

    val start = path[bestSegmentIndex]
    val end = path[bestSegmentIndex + 1]
    val point = LatLng(
        latitude = start.latitude + bestT * (end.latitude - start.latitude),
        longitude = start.longitude + bestT * (end.longitude - start.longitude),
    )
    return PathLocation(bestSegmentIndex, point)
}

/**
 * Splits [path] into `splitPoints.size + 1` consecutive sub-paths: one for
 * the stretch before the first split point, one between each pair of
 * consecutive split points, and one after the last. Each split point is
 * projected onto its nearest segment first, so sub-paths join exactly at
 * that projection — no gap, no overlap. [splitPoints] must already be in
 * the same travel order they occur along [path] (e.g. traffic lights
 * sorted by distanceAlongPath) — each is assumed to project onto a segment
 * at or after the previous one's.
 */
fun splitPathAtPositions(path: List<LatLng>, splitPoints: List<LatLng>): List<List<LatLng>> {
    require(path.size >= 2) { "Path needs at least two points" }

    val segments = mutableListOf<List<LatLng>>()
    var sliceStart = path[0]
    var sliceStartIndex = 0

    for (splitPoint in splitPoints) {
        val location = locateOnPath(path, splitPoint)
        require(location.segmentIndex >= sliceStartIndex) {
            "splitPoints must be in travel order along path"
        }
        val betweenVertices = path.subList(sliceStartIndex + 1, location.segmentIndex + 1)
        segments.add(listOf(sliceStart) + betweenVertices + listOf(location.point))
        sliceStart = location.point
        sliceStartIndex = location.segmentIndex
    }

    segments.add(listOf(sliceStart) + path.subList(sliceStartIndex + 1, path.size))
    return segments
}

/**
 * The point on [path], [distanceMeters] along it measured from
 * `path.first()` — the inverse of [distanceAlongPath]. Walks the path
 * summing segment lengths until the target distance falls inside the
 * current segment, then linearly interpolates within it. Clamps to
 * `path.first()` for a non-positive distance and to `path.last()` for a
 * distance beyond the path's total length, rather than throwing — a caller
 * slicing a fixed-length window (e.g. "the first 20m of this segment")
 * off a segment shorter than that window should get the segment's end, not
 * a crash.
 */
fun pointAtDistanceAlongPath(path: List<LatLng>, distanceMeters: Double): LatLng {
    require(path.size >= 2) { "Path needs at least two points" }
    if (distanceMeters <= 0.0) return path.first()

    var cumulativeDistance = 0.0
    for (i in 0 until path.size - 1) {
        val segmentStart = path[i]
        val segmentEnd = path[i + 1]
        val segmentLength = distanceMeters(segmentStart, segmentEnd)

        if (cumulativeDistance + segmentLength >= distanceMeters) {
            val t = if (segmentLength == 0.0) 0.0 else (distanceMeters - cumulativeDistance) / segmentLength
            return LatLng(
                latitude = segmentStart.latitude + t * (segmentEnd.latitude - segmentStart.latitude),
                longitude = segmentStart.longitude + t * (segmentEnd.longitude - segmentStart.longitude),
            )
        }
        cumulativeDistance += segmentLength
    }

    return path.last()
}
