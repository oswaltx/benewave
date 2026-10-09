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
        assertTrue(box.maxLat - box.minLat <= 0.95 && box.maxLon - box.minLon <= 0.95)
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

    @Test
    fun ownEventsComeBackRoundedAndAreRecognised() {
        // Offline-map position with more digits than the board keeps.
        val light = LatLng(50.936137512345, 6.986519887654)
        val mine = LightEvent(light, 1_000, LightEvent.Kind.STOP_START, 90)
        val fromBoard = mine.copy(light = LatLng(50.9361375, 6.9865199))
        val other = LightEvent(LatLng(50.9361374, 6.9865198), 2_000, LightEvent.Kind.PASS_GREEN, 90)
        val merged = SharedEvents.merge(emptyList(), listOf(fromBoard, other, other.copy()), listOf(mine), nowEpochSec = 3_000)
        assertEquals(listOf(other), merged)
        // And the shared event is found at the device's own (unrounded) light position.
        assertEquals(listOf(other), SharedEvents.near(merged, light))
        assertEquals(emptyList(), SharedEvents.near(merged, LatLng(50.9362, 6.9865)))
    }

    @Test
    fun uploadableDropsTooOldAndFutureEvents() {
        val now = 100L * 86_400
        val ok = ev(koeln, now - 54L * 86_400)
        val old = ev(koeln, now - 56L * 86_400)
        val future = ev(koeln, now + 3_600)
        assertEquals(listOf(ok), SharedEvents.uploadable(listOf(ok, old, future), now))
    }
}
