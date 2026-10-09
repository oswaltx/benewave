package dev.greenwave.shared.algorithm

import kotlin.test.Test
import kotlin.test.assertEquals

class IntersectionsTest {
    private fun reps(ahead: List<Pair<String, Double>>, known: Set<String>) =
        Intersections.representatives(ahead, { it in known }).map { it.first }

    @Test
    fun knownLightBehindUnknownSiblingRepresentsTheIntersection() {
        // Hamburg Hbf: unknown node, live node 17 m further on.
        assertEquals(listOf("live"), reps(listOf("unknown" to 70.0, "live" to 87.0), setOf("live")))
    }

    @Test
    fun withoutKnownLightTheFirstOneRepresents() {
        assertEquals(listOf("a"), reps(listOf("a" to 70.0, "b" to 87.0), emptySet()))
    }

    @Test
    fun lightsFurtherApartAreSeparateIntersections() {
        assertEquals(listOf("a", "b"), reps(listOf("a" to 100.0, "b" to 161.0), setOf("b")))
    }

    @Test
    fun spanIsMeasuredFromTheFirstLightSoLongStreetsDoNotChain() {
        // 40 m apart each: a+b together, c starts a new group.
        assertEquals(listOf("a", "c"), reps(listOf("a" to 0.0, "b" to 40.0, "c" to 80.0), emptySet()))
    }

    @Test
    fun nearestKnownWinsWithinAGroup() {
        assertEquals(listOf("b"), reps(listOf("a" to 10.0, "b" to 20.0, "c" to 30.0), setOf("b", "c")))
    }

    @Test
    fun empty() {
        assertEquals(emptyList(), reps(emptyList(), emptySet()))
    }
}
