package dev.greenwave.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DistanceTest {
    @Test
    fun distanceBetweenPointsOneHundredthDegreeApartAlongAMeridian() {
        val from = LatLng(52.5200, 13.4050)
        val to = LatLng(52.5300, 13.4050)

        val distance = distanceMeters(from, to)

        // A pure north-south move of 0.01 degrees is ~1111.95m on a sphere
        // with the standard mean earth radius (6,371,000m).
        assertTrue(abs(distance - 1111.95) < 1.0, "was $distance")
    }

    @Test
    fun distanceFromAPointToItselfIsZero() {
        val point = LatLng(52.5200, 13.4050)

        assertTrue(distanceMeters(point, point) < 0.001)
    }
}
