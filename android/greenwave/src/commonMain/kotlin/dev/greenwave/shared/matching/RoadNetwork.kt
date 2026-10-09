package dev.greenwave.shared.matching

import dev.greenwave.shared.geo.degToRad
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import kotlin.math.cos
import kotlin.math.floor

/** What kind of way a rider can be on; signals on a carriageway don't govern a separate cycleway. */
enum class WayKind { CARRIAGEWAY, CYCLEWAY, FOOTWAY, PATH, OTHER }

/** A way from the map (OSM way, split as the map stores it) with its geometry. */
data class Way(val id: Long, val points: List<LatLng>, val kind: WayKind)

/**
 * A small routable graph built from [ways] around a stretch of track: way
 * vertices become nodes (vertices within [mergeMeters] of each other are the
 * same node, so ways that meet at a junction connect), consecutive vertices
 * become edges usable in both directions — a bicycle may push or ride against
 * one-way streets often enough that matching must not forbid it.
 */
class RoadNetwork(ways: List<Way>, private val mergeMeters: Double = 1.0) {
    class Edge(val id: Int, val from: Int, val to: Int, val way: Way, val length: Double)

    /** A point on an edge: [fraction] 0 at [Edge.from], 1 at [Edge.to]. */
    data class Position(val edge: Edge, val fraction: Double, val point: LatLng, val distanceMeters: Double)

    val nodes = mutableListOf<LatLng>()
    val edges = mutableListOf<Edge>()
    private val adjacency = mutableListOf<MutableList<Edge>>()
    private val grid = HashMap<Long, MutableList<Int>>()
    private val refLat = ways.firstOrNull()?.points?.firstOrNull()?.latitude ?: 0.0
    private val metersPerDegLon = METERS_PER_DEG * cos(degToRad(refLat))

    init {
        for (way in ways) {
            var prev: Int? = null
            for (p in way.points) {
                val n = nodeAt(p)
                if (prev != null && prev != n) {
                    val e = Edge(edges.size, prev, n, way, distanceMeters(nodes[prev], nodes[n]))
                    edges.add(e)
                    adjacency[prev].add(e)
                    adjacency[n].add(e)
                }
                prev = n
            }
        }
    }

    private fun cell(p: LatLng): Pair<Long, Long> =
        floor(p.latitude * METERS_PER_DEG / CELL).toLong() to floor(p.longitude * metersPerDegLon / CELL).toLong()

    private fun nodeAt(p: LatLng): Int {
        val (cy, cx) = cell(p)
        for (dy in -1L..1L) for (dx in -1L..1L) {
            grid[key(cy + dy, cx + dx)]?.firstOrNull { distanceMeters(nodes[it], p) <= mergeMeters }?.let { return it }
        }
        nodes.add(p)
        adjacency.add(mutableListOf())
        grid.getOrPut(key(cy, cx)) { mutableListOf() }.add(nodes.size - 1)
        return nodes.size - 1
    }

    private fun key(cy: Long, cx: Long) = cy * 1_000_003L + cx

    fun edgesAt(node: Int): List<Edge> = adjacency[node]

    /** The [max] nearest edge positions within [radiusMeters] of [p], nearest first; one per edge. */
    fun candidates(p: LatLng, radiusMeters: Double, max: Int): List<Position> =
        edges.asSequence()
            .map { project(p, it) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            .take(max)
            .toList()

    /** Nearest point on [edge] to [p] (local flat approximation). */
    fun project(p: LatLng, edge: Edge): Position {
        val a = nodes[edge.from]
        val b = nodes[edge.to]
        val ax = (a.longitude - p.longitude) * metersPerDegLon
        val ay = (a.latitude - p.latitude) * METERS_PER_DEG
        val bx = (b.longitude - p.longitude) * metersPerDegLon
        val by = (b.latitude - p.latitude) * METERS_PER_DEG
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val point = LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
        return Position(edge, t, point, distanceMeters(p, point))
    }

    /** Result of routing from one position: distance and predecessor edge per reached node. */
    class Reach(val start: Position, val dist: Map<Int, Double>, val via: Map<Int, Edge>)

    /** Dijkstra from [start] over the network, up to [maxMeters]. */
    fun reach(start: Position, maxMeters: Double): Reach {
        val dist = HashMap<Int, Double>()
        val via = HashMap<Int, Edge>()
        val queue = MinHeap<Pair<Double, Int>>(compareBy { it.first })
        fun offer(node: Int, d: Double, edge: Edge?) {
            if (d > maxMeters || d >= (dist[node] ?: Double.MAX_VALUE)) return
            dist[node] = d
            if (edge != null) via[node] = edge else via.remove(node)
            queue.add(d to node)
        }
        offer(start.edge.from, start.fraction * start.edge.length, null)
        offer(start.edge.to, (1 - start.fraction) * start.edge.length, null)
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()
            if (d > (dist[n] ?: Double.MAX_VALUE)) continue
            for (e in adjacency[n]) {
                val other = if (e.from == n) e.to else e.from
                offer(other, d + e.length, e)
            }
        }
        return Reach(start, dist, via)
    }

    /**
     * Network distance from [reach]'s start to [target], and the nodes passed on
     * the way (in order), or null beyond the search bound.
     */
    fun route(reach: Reach, target: Position): Pair<Double, List<Int>>? {
        val s = reach.start
        if (s.edge.id == target.edge.id) {
            return kotlin.math.abs(target.fraction - s.fraction) * s.edge.length to emptyList()
        }
        val viaFrom = reach.dist[target.edge.from]?.let { it + target.fraction * target.edge.length }
        val viaTo = reach.dist[target.edge.to]?.let { it + (1 - target.fraction) * target.edge.length }
        val (d, end) = when {
            viaFrom == null && viaTo == null -> return null
            viaTo == null || (viaFrom != null && viaFrom <= viaTo) -> viaFrom!! to target.edge.from
            else -> viaTo to target.edge.to
        }
        val path = ArrayDeque<Int>()
        var n = end
        path.addFirst(n)
        while (true) {
            val e = reach.via[n] ?: break
            n = if (e.from == n) e.to else e.from
            path.addFirst(n)
        }
        return d to path.toList()
    }

    private companion object {
        const val METERS_PER_DEG = 111_320.0
        const val CELL = 2.0
    }
}
