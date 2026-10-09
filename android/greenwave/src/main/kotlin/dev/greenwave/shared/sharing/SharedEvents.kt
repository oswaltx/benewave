package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.LightEvent
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Bounding box in degrees. */
data class GeoBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/** Helpers for the events other riders shared. */
object SharedEvents {
    /** Shared events older than this are of no use for learning (the estimator looks back 28 days). */
    const val MAX_AGE_SEC = 60L * 86_400

    /**
     * The area to fetch shared events for: around the rider's own lights within
     * [radiusMeters] of the most recent one (so a trip to another city does not
     * blow up the box), plus [marginMeters], at most [maxSideDeg] per side.
     * Null without own events.
     */
    fun region(
        own: List<LightEvent>,
        radiusMeters: Double = 30_000.0,
        marginMeters: Double = 2_000.0,
        maxSideDeg: Double = 1.0,
    ): GeoBox? {
        val latest = own.maxByOrNull { it.epochSec }?.light ?: return null
        val near = own.map { it.light }.filter { distanceMeters(it, latest) <= radiusMeters }
        val dLat = marginMeters / 111_320.0
        val dLon = marginMeters / (111_320.0 * cos(Math.toRadians(latest.latitude)).coerceAtLeast(0.1))
        var box = GeoBox(
            minLat = near.minOf { it.latitude } - dLat,
            minLon = near.minOf { it.longitude } - dLon,
            maxLat = near.maxOf { it.latitude } + dLat,
            maxLon = near.maxOf { it.longitude } + dLon,
        )
        box = clampSide(box, latest, maxSideDeg)
        return box
    }

    private fun clampSide(box: GeoBox, center: LatLng, maxSideDeg: Double): GeoBox {
        val half = maxSideDeg / 2
        val lat = if (box.maxLat - box.minLat <= maxSideDeg) box.minLat to box.maxLat
        else max(box.minLat, center.latitude - half) to min(box.maxLat, center.latitude + half)
        val lon = if (box.maxLon - box.minLon <= maxSideDeg) box.minLon to box.maxLon
        else max(box.minLon, center.longitude - half) to min(box.maxLon, center.longitude + half)
        return GeoBox(lat.first, lon.first, lat.second, lon.second)
    }

    /**
     * The shared store after a download: what was there plus [downloaded],
     * without duplicates, without the rider's [own] events coming back from
     * the board, and without events older than [MAX_AGE_SEC] at [nowEpochSec].
     */
    fun merge(existing: List<LightEvent>, downloaded: List<LightEvent>, own: List<LightEvent>, nowEpochSec: Long): List<LightEvent> {
        val ownKeys = own.mapTo(HashSet()) { key(it) }
        val seen = HashSet<Key>()
        return (existing + downloaded)
            .filter { nowEpochSec - it.epochSec <= MAX_AGE_SEC && key(it) !in ownKeys && seen.add(key(it)) }
            .sortedBy { it.epochSec }
    }

    private data class Key(val light: LatLng, val epochSec: Long, val kind: LightEvent.Kind)

    private fun key(e: LightEvent) = Key(e.light, e.epochSec, e.kind)
}
