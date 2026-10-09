package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class EdgeTrimTest {
    private val home = LatLng(50.9, 6.95)
    private fun north(m: Double) = LatLng(home.latitude + m / 111_320.0, home.longitude)
    private fun ev(at: LatLng) = LightEvent(at, 0, LightEvent.Kind.PASS_GREEN, 0)

    @Test
    fun dropsEventsNearStartAndEndKeepsTheMiddle() {
        val trim = EdgeTrim()
        assertEquals(emptyList(), trim.onUpdate(north(0.0), emptyList()))
        // Light 80 m from the start: dropped.
        assertEquals(emptyList(), trim.onUpdate(north(120.0), listOf(ev(north(80.0)))))
        // Light at 1 km: held until the rider is 150 m past it.
        assertEquals(emptyList(), trim.onUpdate(north(1_040.0), listOf(ev(north(1_000.0)))))
        assertEquals(1, trim.onUpdate(north(1_200.0), emptyList()).size)
        // Light 60 m before the end of the ride: never released, dropped at the end.
        assertEquals(emptyList(), trim.onUpdate(north(2_000.0), listOf(ev(north(1_960.0)))))
        assertEquals(1, trim.end())
    }
}
