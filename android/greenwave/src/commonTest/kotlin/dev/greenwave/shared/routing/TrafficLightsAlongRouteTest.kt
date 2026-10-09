package dev.greenwave.shared.routing

import dev.greenwave.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficLightsAlongRouteTest {
    // ~1.1 km due east along latitude 50.94 (Köln).
    private val path = listOf(LatLng(50.94, 6.95), LatLng(50.94, 6.965))

    @Test
    fun keepsOnlyLightsNearThePathOrderedByProgress() {
        val far = LatLng(50.945, 6.955) // ~550 m north
        val second = LatLng(50.94, 6.96)
        val first = LatLng(50.9401, 6.952) // ~11 m off the path
        val result = TrafficLightsAlongRoute.select(path, listOf(far, second, first))
        assertEquals(listOf(first, second), result)
    }

    @Test
    fun collapsesSignalsOfOneJunction() {
        val a = LatLng(50.94, 6.955)
        val b = LatLng(50.94, 6.9553) // ~21 m further along
        assertEquals(listOf(a), TrafficLightsAlongRoute.select(path, listOf(b, a)))
    }

    @Test
    fun queryUsesPolylineAroundAndThinsLongPaths() {
        val long = List(1000) { LatLng(50.94, 6.95 + it * 0.0001) }
        val q = TrafficLightsAlongRoute.overpassQuery(long, maxPoints = 100)
        assertTrue(q.contains("around:40,50.940000,6.950000,"))
        val coordCount = q.substringAfter("around:40,").substringBefore(")").split(",").size / 2
        assertTrue(coordCount in 100..101, "coords: $coordCount")
    }
}
