package dev.greenwave.shared.algorithm

/**
 * OSM often has several traffic signal nodes at one intersection (one per
 * carriageway or approach), a few metres apart along the route. They run on
 * one program, so for the speed advice they count as one light: lights ahead
 * within [MAX_SPAN_METERS] of the first one form a group, and the group is
 * represented by its nearest known light (live or learned), else by its first
 * light. Without this an unknown sibling right before a live light hides the
 * advice for the whole intersection.
 */
object Intersections {
    /** Large junctions with dual carriageways span about this much along a route. */
    const val MAX_SPAN_METERS = 60.0

    /**
     * [ahead]: lights with their distance along the route, sorted by distance.
     * Returns one entry per intersection, in the same order.
     */
    fun <T> representatives(
        ahead: List<Pair<T, Double>>,
        isKnown: (T) -> Boolean,
        maxSpanMeters: Double = MAX_SPAN_METERS,
    ): List<Pair<T, Double>> {
        val result = mutableListOf<Pair<T, Double>>()
        var i = 0
        while (i < ahead.size) {
            val start = ahead[i].second
            var end = i
            while (end + 1 < ahead.size && ahead[end + 1].second - start <= maxSpanMeters) end++
            val group = ahead.subList(i, end + 1)
            result.add(group.firstOrNull { isKnown(it.first) } ?: group.first())
            i = end + 1
        }
        return result
    }
}
