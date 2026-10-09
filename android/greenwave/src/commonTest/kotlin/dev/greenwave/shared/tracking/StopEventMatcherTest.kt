package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StopEventMatcherTest {

    private val light = LatLng(52.5200, 13.4050)
    private val lightAcrossStreet = LatLng(52.5200, 13.4051)  // ~6m east

    @Test
    fun emptyCandidateListReturnsNull() {
        val result = StopEventMatcher.match(
            eventPosition = light,
            candidatePositions = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun matchWithinRadius() {
        // Rider stopped ~5 m off the known node — well inside 30 m.
        val eventPosition = LatLng(52.5200, 13.40505)
        val result = StopEventMatcher.match(eventPosition, listOf(light))
        assertEquals(light, result)
    }

    @Test
    fun noMatchWhenOutsideRadius() {
        // 50 m north — outside the default 30 m radius.
        val eventPosition = LatLng(52.5205, 13.4050)
        val result = StopEventMatcher.match(eventPosition, listOf(light))
        assertNull(result)
    }

    @Test
    fun nearestCandidateWins() {
        // Stop midway between two known nodes — picks the closer one.
        val farNode = LatLng(52.5210, 13.4050)
        val eventPosition = LatLng(52.5201, 13.4050)  // ~14 m from near, ~100 m from far
        val result = StopEventMatcher.match(eventPosition, listOf(farNode, light))
        assertEquals(light, result)
    }

    @Test
    fun respectsCustomRadius() {
        val eventPosition = LatLng(52.5200, 13.4051)  // ~6 m east
        // Default 30 m radius still matches.
        assertEquals(light, StopEventMatcher.match(eventPosition, listOf(light)))
        // Tightened to 3 m radius rejects the same event (across a street).
        assertNull(
            StopEventMatcher.match(
                eventPosition = eventPosition,
                candidatePositions = listOf(light),
                maxRadiusMeters = 3.0,
            ),
        )
    }
}