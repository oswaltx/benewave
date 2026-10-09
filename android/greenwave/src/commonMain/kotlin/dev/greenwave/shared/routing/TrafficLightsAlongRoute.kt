package dev.greenwave.shared.routing

import dev.greenwave.shared.geo.sixDecimals
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.geo.distanceToPath

/**
 * Turns raw `highway=traffic_signals` nodes near a route into the ordered list
 * of lights the rider actually passes: same rules as the greenWave app's
 * Overpass lookup (within [MAX_DISTANCE_TO_ROUTE_METERS] of the path, sorted by
 * progress, clusters closer than [CLUSTER_THRESHOLD_METERS] along the route
 * collapsed to their first node — one junction often has a signal per arm).
 */
object TrafficLightsAlongRoute {
    const val MAX_DISTANCE_TO_ROUTE_METERS = 35.0
    const val CLUSTER_THRESHOLD_METERS = 50.0

    fun select(path: List<LatLng>, candidates: List<LatLng>): List<LatLng> {
        if (path.size < 2) return emptyList()
        val sortedByProgress = candidates
            .filter { distanceToPath(path, it) < MAX_DISTANCE_TO_ROUTE_METERS }
            .map { it to distanceAlongPath(path, it) }
            .sortedBy { it.second }
        return mergeNearbyByProgress(sortedByProgress, CLUSTER_THRESHOLD_METERS)
    }

    /**
     * Overpass QL for signals within [radiusMeters] of the path. Uses the
     * polyline form of `around` so long routes don't pull in a whole
     * bounding box; the path is thinned to at most [maxPoints] vertices to keep
     * the query small (the radius covers the shortcut).
     */
    fun overpassQuery(path: List<LatLng>, radiusMeters: Int = 40, maxPoints: Int = 400): String {
        val step = maxOf(1, (path.size + maxPoints - 1) / maxPoints)
        val thinned = path.filterIndexed { i, _ -> i % step == 0 || i == path.lastIndex }
        val coords = thinned.joinToString(",") { "${sixDecimals(it.latitude)},${sixDecimals(it.longitude)}" }
        return """[out:json][timeout:25];node["highway"="traffic_signals"](around:$radiusMeters,$coords);out;"""
    }
}

internal fun mergeNearbyByProgress(
    sortedByProgress: List<Pair<LatLng, Double>>,
    thresholdMeters: Double,
): List<LatLng> {
    val kept = mutableListOf<Pair<LatLng, Double>>()
    for (candidate in sortedByProgress) {
        if (kept.isEmpty() || candidate.second - kept.last().second >= thresholdMeters) {
            kept.add(candidate)
        }
    }
    return kept.map { it.first }
}
