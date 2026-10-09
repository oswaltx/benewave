package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedEventsTest {
    private val koeln = LatLng(50.93, 6.96)
    private val hamburg = LatLng(53.55, 9.99)
    private fun ev(at: LatLng, t: Long, kind: LightEvent.Kind = LightEvent.Kind.PASS_GREEN) = LightEvent(at, t, kind, 90)

    @Test
    fun regionCoversRecentCityOnly() {
        val own = listOf(ev(hamburg, 100), ev(koeln, 200), ev(LatLng(50.95, 6.99), 300))
        val box = SharedEvents.region(own)!!
        assertTrue(box.minLat < 50.93 && box.maxLat > 50.95 && box.maxLon > 6.99)
        assertTrue(box.maxLat < 51.5, "Hamburg must not be included: $box")
        assertTrue(box.maxLat - box.minLat <= 1.0 && box.maxLon - box.minLon <= 1.0)
    }

    @Test
    fun noOwnEventsNoRegion() {
        assertNull(SharedEvents.region(emptyList()))
    }

    @Test
    fun mergeDropsDuplicatesOwnAndOld() {
        val now = 100L * 86_400
        val a = ev(koeln, now - 10)
        val b = ev(koeln, now - 20, LightEvent.Kind.STOP_START)
        val mine = ev(koeln, now - 30)
        val old = ev(koeln, now - 61L * 86_400)
        val merged = SharedEvents.merge(existing = listOf(a), downloaded = listOf(a, b, mine, old), own = listOf(mine), nowEpochSec = now)
        assertEquals(listOf(b, a), merged)
    }
}
