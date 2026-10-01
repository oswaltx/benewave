package dev.greenwave.maps

import app.organicmaps.sdk.Framework
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.matching.Way
import dev.greenwave.shared.matching.WayKind
import kotlin.math.cos

/**
 * Traffic signals from the downloaded CoMaps maps. Local, fast, and nothing
 * leaves the device. Empty where no map is downloaded; callers fall back to
 * [OverpassClient] then. Blocking — call it off the main thread.
 */
internal object OfflineSignals {
    /** Route chunks are queried as boxes of about this size, so a long route doesn't scan one huge box. */
    private const val CHUNK_METERS = 2_000.0

    /** Signals within this distance of the path; [dev.greenwave.shared.routing.TrafficLightsAlongRoute.select] narrows it down. */
    private const val PATH_MARGIN_METERS = 50.0

    fun alongPath(path: List<LatLng>): List<LatLng> {
        if (path.isEmpty()) return emptyList()
        val found = LinkedHashSet<LatLng>()
        var chunk = mutableListOf(path.first())
        for (point in path.drop(1)) {
            chunk.add(point)
            if (distanceMeters(chunk.first(), point) >= CHUNK_METERS) {
                found.addAll(inBox(chunk, PATH_MARGIN_METERS))
                chunk = mutableListOf(point)
            }
        }
        if (chunk.isNotEmpty()) found.addAll(inBox(chunk, PATH_MARGIN_METERS))
        return found.toList()
    }

    fun around(center: LatLng, radiusMeters: Double): List<LatLng> = inBox(listOf(center), radiusMeters)

    private fun inBox(points: List<LatLng>, marginMeters: Double): List<LatLng> {
        val midLat = points.sumOf { it.latitude } / points.size
        val dLat = marginMeters / METERS_PER_DEGREE
        val dLon = marginMeters / (METERS_PER_DEGREE * cos(Math.toRadians(midLat)))
        val raw = Framework.nativeGreenWaveTrafficSignals(
            points.minOf { it.latitude } - dLat,
            points.minOf { it.longitude } - dLon,
            points.maxOf { it.latitude } + dLat,
            points.maxOf { it.longitude } + dLon,
        )
        return List(raw.size / 2) { LatLng(latitude = raw[2 * it], longitude = raw[2 * it + 1]) }
    }

    private const val METERS_PER_DEGREE = 111_320.0
}

/**
 * Bicycle-usable ways from the downloaded maps, for map matching the rider's
 * track (see [dev.greenwave.shared.matching.MapMatcher]). Empty where no map is
 * downloaded. Blocking, fine on the worker for a few hundred metres.
 */
internal object OfflineWays {
    fun around(points: List<LatLng>, marginMeters: Double): List<Way> {
        if (points.isEmpty()) return emptyList()
        val midLat = points.sumOf { it.latitude } / points.size
        val dLat = marginMeters / 111_320.0
        val dLon = marginMeters / (111_320.0 * cos(Math.toRadians(midLat)))
        val raw = Framework.nativeGreenWaveWays(
            points.minOf { it.latitude } - dLat,
            points.minOf { it.longitude } - dLon,
            points.maxOf { it.latitude } + dLat,
            points.maxOf { it.longitude } + dLon,
        )
        val ways = mutableListOf<Way>()
        var i = 1
        repeat(raw.firstOrNull()?.toInt() ?: 0) {
            val kind = KINDS.getOrElse(raw[i].toInt()) { WayKind.OTHER }
            val id = raw[i + 1].toLong()
            val n = raw[i + 2].toInt()
            i += 3
            ways.add(Way(id, List(n) { k -> LatLng(latitude = raw[i + 2 * k], longitude = raw[i + 2 * k + 1]) }, kind))
            i += 2 * n
        }
        return ways
    }

    private val KINDS = listOf(WayKind.CARRIAGEWAY, WayKind.CYCLEWAY, WayKind.FOOTWAY, WayKind.PATH, WayKind.OTHER)
}
