package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareTrimTest {
    private val home = LatLng(50.9, 6.95)
    private fun north(m: Double) = LatLng(home.latitude + m / 111_320.0, home.longitude)
    private fun ev(at: LatLng) = LightEvent(at, 1_000, LightEvent.Kind.PASS_GREEN, 0)

    @Test
    fun keepsOnlyEventsOutsideBothRadii() {
        val trim = ShareTrim(Random(1))
        assertTrue(trim.startRadiusMeters in 400.0..600.0 && trim.endRadiusMeters in 400.0..600.0)
        trim.onFix(north(0.0))
        trim.add(listOf(ev(north(300.0)), ev(north(1_000.0)), ev(north(2_000.0)), ev(north(2_700.0))))
        trim.onFix(north(3_000.0))
        // 300 m from start and 300 m from the end are always inside; 1 km and 2 km never.
        assertEquals(listOf(north(1_000.0), north(2_000.0)), trim.end().map { it.light })
    }

    @Test
    fun radiusVariesBetweenRides() {
        val radii = (1..20).map { ShareTrim(Random(it)).startRadiusMeters }
        assertTrue(radii.distinct().size > 15)
        assertTrue(radii.all { it in 400.0..600.0 })
    }

    @Test
    fun endStartsAfreshAndRideWithoutFixesSharesNothing() {
        val trim = ShareTrim(Random(2))
        trim.add(listOf(ev(north(1_000.0))))
        assertEquals(emptyList(), trim.end())
        trim.onFix(north(0.0))
        trim.onFix(north(3_000.0))
        assertEquals(emptyList(), trim.end())
    }

    @Test
    fun roundTripSharesNothingNearHome() {
        // Out and back: start and end are both at home, so lights near home stay private on both ends.
        val trim = ShareTrim(Random(3))
        trim.onFix(home)
        trim.add(listOf(ev(north(350.0)), ev(north(1_500.0))))
        trim.onFix(north(3_000.0))
        trim.onFix(north(100.0))
        assertEquals(listOf(north(1_500.0)), trim.end().map { it.light })
    }
}
