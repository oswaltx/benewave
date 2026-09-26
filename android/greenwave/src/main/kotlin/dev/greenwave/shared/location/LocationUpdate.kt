package dev.greenwave.shared.location

import dev.greenwave.shared.geo.LatLng

data class LocationUpdate(
    val position: LatLng,
    val epochSec: Long,
    val speedKmh: Double,
    val bearingDegrees: Float?,
)
