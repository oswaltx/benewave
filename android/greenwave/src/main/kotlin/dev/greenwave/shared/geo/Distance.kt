package dev.greenwave.shared.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Great-circle distance between two coordinates, in meters. */
fun distanceMeters(from: LatLng, to: LatLng): Double {
    val dLat = toRadians(to.latitude - from.latitude)
    val dLon = toRadians(to.longitude - from.longitude)
    val lat1 = toRadians(from.latitude)
    val lat2 = toRadians(to.latitude)

    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return EARTH_RADIUS_METERS * c
}

internal fun toRadians(degrees: Double): Double = degrees * kotlin.math.PI / 180.0
