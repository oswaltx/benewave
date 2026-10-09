package dev.greenwave.shared.analysis

import dev.greenwave.shared.geo.degToRad
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import kotlin.math.cos
import kotlin.math.floor

/** One recorded ride as the way comparison needs it. */
data class RideWay(
    val startEpochSec: Long,
    /** Door to door, stops included: that is what a faster way has to beat. */
    val durationSec: Long,
    val lengthMeters: Double,
    val path: List<LatLng>,
    /** The first route the app suggested, if the ride was a navigation. */
    val suggested: List<LatLng>? = null,
)

/** Rides between the same two places along the same way. */
data class WayStats(
    val rides: List<RideWay>,
    val meanDurationSec: Long,
    val meanLengthMeters: Double,
    /** Some ride on this connection was navigated and the app suggested this way. */
    val suggestedByNavigation: Boolean,
)

/** All ways ridden from about [from] to about [to], fastest first. */
data class Connection(val from: LatLng, val to: LatLng, val ways: List<WayStats>) {
    val rideCount: Int get() = ways.sumOf { it.rides.size }
}

/**
 * Which way between two places is faster on average ("Weg X im Schnitt
 * schneller", David 26./27.09.): rides whose start and end lie within
 * [endpointMeters] of each other form a connection (direction matters);
 * within it, rides that share at least [sameWayShare] of their ~[cellMeters]
 * grid cells count as the same way. Only connections ridden at least twice are
 * returned, those with several ways first.
 */
object WayComparison {
    fun compare(
        rides: List<RideWay>,
        endpointMeters: Double = 300.0,
        sameWayShare: Double = 0.6,
        cellMeters: Double = 100.0,
    ): List<Connection> {
        val usable = rides.filter { it.path.size >= 2 && it.durationSec > 0 }.sortedBy { it.startEpochSec }
        val groups = mutableListOf<MutableList<RideWay>>()
        for (ride in usable) {
            val group = groups.firstOrNull {
                val first = it.first()
                distanceMeters(first.path.first(), ride.path.first()) <= endpointMeters &&
                    distanceMeters(first.path.last(), ride.path.last()) <= endpointMeters
            }
            if (group != null) group.add(ride) else groups.add(mutableListOf(ride))
        }
        return groups.filter { it.size >= 2 }.map { group -> connection(group, sameWayShare, cellMeters) }
            .sortedWith(compareByDescending<Connection> { it.ways.size }.thenByDescending { it.rideCount })
    }

    private fun connection(group: List<RideWay>, share: Double, cellMeters: Double): Connection {
        val origin = group.first().path.first()
        val cells = group.associateWith { cellsOf(it.path, origin, cellMeters) }
        val ways = mutableListOf<MutableList<RideWay>>()
        for (ride in group) {
            val way = ways.firstOrNull { similarity(cells.getValue(it.first()), cells.getValue(ride)) >= share }
            if (way != null) way.add(ride) else ways.add(mutableListOf(ride))
        }
        val suggestions = group.mapNotNull { it.suggested?.takeIf { s -> s.size >= 2 } }.map { cellsOf(it, origin, cellMeters) }
        val stats = ways.map { members ->
            val own = cellsOf(members.first().path, origin, cellMeters)
            WayStats(
                rides = members,
                meanDurationSec = members.map { it.durationSec }.average().toLong(),
                meanLengthMeters = members.map { it.lengthMeters }.average(),
                suggestedByNavigation = suggestions.any { similarity(it, own) >= share },
            )
        }.sortedBy { it.meanDurationSec }
        return Connection(from = origin, to = group.first().path.last(), ways = stats)
    }

    /** Grid cells (about [cellMeters] square) the path runs through, with points filled in along long segments. */
    internal fun cellsOf(path: List<LatLng>, origin: LatLng, cellMeters: Double): Set<Long> {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cos(degToRad(origin.latitude)).coerceAtLeast(0.1)
        fun cell(p: LatLng): Long {
            val x = floor((p.longitude - origin.longitude) * metersPerDegLon / cellMeters).toLong()
            val y = floor((p.latitude - origin.latitude) * metersPerDegLat / cellMeters).toLong()
            return (x shl 32) xor (y and 0xffffffffL)
        }
        val out = HashSet<Long>()
        for (i in path.indices) {
            out.add(cell(path[i]))
            if (i == 0) continue
            val a = path[i - 1]
            val b = path[i]
            val steps = (distanceMeters(a, b) / (cellMeters / 2)).toInt()
            for (s in 1 until steps) {
                val f = s.toDouble() / steps
                out.add(cell(LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)))
            }
        }
        return out
    }

    /** Share of cells in common (Jaccard). */
    internal fun similarity(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }
}
