package dev.greenwave.shared.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos

private const val METERS_PER_DEGREE = 111_320.0

/**
 * Compass bearing from [from] to [to] in [0, 360), degrees clockwise from north.
 * Flat-earth approximation, fine for the few hundred metres around a junction.
 */
fun bearingDegrees(from: LatLng, to: LatLng): Double {
    val dx = (to.longitude - from.longitude) * METERS_PER_DEGREE * cos(from.latitude * PI / 180.0)
    val dy = (to.latitude - from.latitude) * METERS_PER_DEGREE
    return (atan2(dx, dy) * 180.0 / PI + 360.0) % 360.0
}

/** Signed difference [to] − [from] in (−180, 180]: positive = clockwise (turning right). */
fun angleDifference(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d <= -180.0) d += 360.0
    if (d > 180.0) d -= 360.0
    return d
}
