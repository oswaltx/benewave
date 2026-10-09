package dev.greenwave.shared.analysis

import dev.greenwave.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WayComparisonTest {
    private val home = LatLng(50.93, 6.96)
    private fun at(northM: Double, eastM: Double) =
        LatLng(home.latitude + northM / 111_320.0, home.longitude + eastM / (111_320.0 * Math.cos(Math.toRadians(home.latitude))))

    // Two ways from home to a point 2 km north: straight, or a detour 600 m to the east.
    private val straight = listOf(at(0.0, 0.0), at(1_000.0, 0.0), at(2_000.0, 0.0))
    private val detour = listOf(at(0.0, 0.0), at(0.0, 600.0), at(2_000.0, 600.0), at(2_000.0, 0.0))

    private fun ride(t: Long, path: List<LatLng>, min: Long, suggested: List<LatLng>? = null) =
        RideWay(startEpochSec = t, durationSec = min * 60, lengthMeters = 0.0, path = path, suggested = suggested)

    @Test
    fun groupsWaysAndSortsFastestFirst() {
        val rides = listOf(
            ride(1, straight, 12, suggested = straight),
            ride(2, detour, 10),
            ride(3, straight, 14),
            ride(4, detour, 9),
        )
        val c = WayComparison.compare(rides).single()
        assertEquals(2, c.ways.size)
        val (fast, slow) = c.ways
        assertEquals(listOf(2L, 4L), fast.rides.map { it.startEpochSec })
        assertEquals(570, fast.meanDurationSec)
        assertEquals(780, slow.meanDurationSec)
        assertFalse(fast.suggestedByNavigation)
        assertTrue(slow.suggestedByNavigation)
    }

    @Test
    fun directionAndPlacesSeparateConnections() {
        val back = straight.reversed()
        val elsewhere = straight.map { LatLng(it.latitude + 0.05, it.longitude) }
        val rides = listOf(ride(1, straight, 10), ride(2, back, 11), ride(3, elsewhere, 9), ride(4, straight, 12))
        val c = WayComparison.compare(rides)
        assertEquals(1, c.size, "only the connection ridden twice")
        assertEquals(2, c.single().rideCount)
    }

    @Test
    fun gpsNoiseStaysTheSameWay() {
        val noisy = straight.map { LatLng(it.latitude, it.longitude + 20 / (111_320.0 * 0.63)) }
        val c = WayComparison.compare(listOf(ride(1, straight, 10), ride(2, noisy, 11))).single()
        assertEquals(1, c.ways.size)
    }
}
