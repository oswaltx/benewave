package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedEventsTest {
    private val koeln = LatLng(50.93, 6.96)
    private val hamburg = LatLng(53.55, 9.99)
    private fun ev(at: LatLng, t: Long, kind: LightEvent.Kind = LightEvent.Kind.PASS_GREEN) = LightEvent(at, t, kind, 90)

    @Test
    fun tilesCoverRecentCityOnlyNearestFirst() {
        val own = listOf(ev(hamburg, 100), ev(koeln, 200), ev(LatLng(50.95, 6.99), 300))
        val tiles = SharedEvents.tiles(own)
        assertEquals(Tile(203, 27), tiles.first(), "the tile of the most recent light first")
        assertTrue(Tile(203, 27) in tiles && SharedEvents.tileOf(koeln) in tiles)
        assertTrue(tiles.none { it.latIdx >= 210 }, "Hamburg must not be included: $tiles")
        // Köln at 50.93 lies 2 km from no tile edge: one tile; the 2 km margin only adds tiles near edges.
        assertEquals(listOf(Tile(203, 27)), SharedEvents.tiles(listOf(ev(LatLng(50.87, 6.87), 1))))
        assertEquals(2, SharedEvents.tiles(listOf(ev(LatLng(50.99, 6.87), 1))).size)
    }

    @Test
    fun tilesAreCappedAndNegativeCoordinatesRoundDown() {
        val spread = (0 until 40).map { i -> ev(LatLng(50.8 + (i % 8) * 0.03, 6.7 + (i / 8) * 0.06), i.toLong()) }
        assertTrue(SharedEvents.tiles(spread, radiusMeters = 100_000.0, maxTiles = 1_000).size > 3)
        assertEquals(3, SharedEvents.tiles(spread, radiusMeters = 100_000.0, maxTiles = 3).size)
        assertEquals(Tile(-1, -1), SharedEvents.tileOf(LatLng(-0.1, -0.1)))
    }

    @Test
    fun noOwnEventsNoTiles() {
        assertEquals(emptyList(), SharedEvents.tiles(emptyList()))
    }

    @Test
    fun credibleNeedsTwoSourcesPerLight() {
        val light = LatLng(50.9361375, 6.9865199)
        val sameLightOtherMap = LatLng(50.9361390, 6.9865210) // < 3 m away
        val forged = (0 until 10).map { SourcedEvent(ev(koeln, it * 100L), "a") }
        assertEquals(emptyList(), SharedEvents.credible(forged, own = emptyList()), "six or more events from one source alone never count")
        val two = listOf(SourcedEvent(ev(light, 10), "a"), SourcedEvent(ev(sameLightOtherMap, 20), "b"))
        assertEquals(two.map { it.event }, SharedEvents.credible(two, own = emptyList()))
        // A second source at another light does not help.
        val elsewhere = listOf(SourcedEvent(ev(light, 10), "a"), SourcedEvent(ev(LatLng(50.9363, 6.9865), 20), "b"))
        assertEquals(emptyList(), SharedEvents.credible(elsewhere, own = emptyList()))
        // The same name in two tiles' answers is not two sources, nor are two names across tiles.
        val acrossTiles = listOf(SourcedEvent(ev(light, 10), "a", "t1"), SourcedEvent(ev(sameLightOtherMap, 20), "b", "t2"))
        assertEquals(emptyList(), SharedEvents.credible(acrossTiles, own = emptyList()))
    }

    @Test
    fun ownEventsComingBackAreNoSource() {
        val mine = ev(koeln, 10)
        val forged = SourcedEvent(ev(koeln, 20), "x", "c")
        // The board returns the rider's own event under some name; with one forger that would look like two sources.
        assertEquals(emptyList(), SharedEvents.credible(listOf(SourcedEvent(mine, "y", "c"), forged), own = listOf(mine)))
        val second = SourcedEvent(ev(koeln, 30), "z", "c")
        assertEquals(listOf(forged.event, second.event), SharedEvents.credible(listOf(SourcedEvent(mine, "y", "c"), forged, second), own = listOf(mine)))
    }

    @Test
    fun credibleCapsWhatOneSourceAddsToALight() {
        val flood = (0 until 100).map { SourcedEvent(ev(koeln, it.toLong()), "a") }
        val other = SourcedEvent(ev(koeln, 1_000), "b")
        val kept = SharedEvents.credible(flood + other, own = emptyList())
        assertEquals(SharedEvents.MAX_PER_SOURCE_AND_LIGHT + 1, kept.size)
        assertTrue(kept.containsAll((70 until 100).map { ev(koeln, it.toLong()) }), "the newest of each source")
    }

    @Test
    fun plausibleDropsJumpsTooFastForABike() {
        val a = ev(koeln, 1_000)
        val jump = ev(LatLng(50.948, 6.96), 1_030) // 2 km in 30 s
        val b = ev(LatLng(50.931, 6.96), 1_060)    // 110 m in 60 s from a
        val sameJunction = ev(LatLng(50.93005, 6.96), 1_000)
        val later = ev(LatLng(50.948, 6.96), 1_400) // the far light 400 s later: fine
        assertEquals(listOf(a, sameJunction, b, later), SharedEvents.plausible(listOf(later, jump, b, a, sameJunction)))
    }

    @Test
    fun anOutlierFirstDoesNotCostTheRealEventsAfterIt() {
        val glitch = ev(LatLng(50.99, 6.96), 990)  // 6.7 km away 10 s before
        val real = (0 until 5).map { ev(LatLng(50.93 + it * 0.001, 6.96), 1_000L + it * 30) }
        assertEquals(real, SharedEvents.plausible(real + glitch))
        val glitchLast = ev(LatLng(50.99, 6.96), 1_130)
        assertEquals(real, SharedEvents.plausible(real + glitchLast))
        val glitchMiddle = ev(LatLng(50.99, 6.96), 1_045)
        assertEquals(real, SharedEvents.plausible(real + glitchMiddle))
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
