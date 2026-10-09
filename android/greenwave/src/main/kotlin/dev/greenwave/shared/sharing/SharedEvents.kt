package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.LightEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Bounding box in degrees. */
data class GeoBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/** Helpers for the events other riders shared. */
object SharedEvents {
    /** Shared events older than this are of no use for learning (the estimator looks back 28 days). */
    const val MAX_AGE_SEC = 60L * 86_400

    /** Only events younger than this leave the device, so the board (which takes 60 days) never rejects them. */
    const val MAX_UPLOAD_AGE_SEC = 55L * 86_400

    /** Events stamped further in the future (wrong clock) stay on the device. */
    const val MAX_FUTURE_SEC = 30L * 60

    /**
     * The same light seen by different devices: positions from the offline map
     * and from Overpass differ in the last digits, and the board rounds to 7
     * decimals, so lights are matched within this distance, never by equality.
     */
    const val MATCH_METERS = 3.0

    /** Of the queued [events], those the board will accept at [nowEpochSec]. */
    fun uploadable(events: List<LightEvent>, nowEpochSec: Long): List<LightEvent> =
        events.filter { it.epochSec in (nowEpochSec - MAX_UPLOAD_AGE_SEC)..(nowEpochSec + MAX_FUTURE_SEC) }

    /** Of [events], those at the light at [position] (within [MATCH_METERS]). */
    fun near(events: List<LightEvent>, position: LatLng, meters: Double = MATCH_METERS): List<LightEvent> {
        // About 3x the radius in degrees of latitude, a cheap pre-filter before the exact distance.
        val dLat = meters * 3 / 111_320.0
        return events.filter { abs(it.light.latitude - position.latitude) <= dLat && distanceMeters(it.light, position) <= meters }
    }

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
        // Below the board's limit of 1° so rounding of the request never exceeds it.
        maxSideDeg: Double = 0.95,
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
        val ownByMoment = own.groupBy { it.epochSec to it.kind }
        val seen = HashSet<Key>()
        return (existing + downloaded)
            .filter { e ->
                nowEpochSec - e.epochSec <= MAX_AGE_SEC &&
                    ownByMoment[e.epochSec to e.kind].orEmpty().none { distanceMeters(it.light, e.light) <= MATCH_METERS } &&
                    seen.add(key(e))
            }
            .sortedBy { it.epochSec }
    }

    private data class Key(val lat: Long, val lon: Long, val epochSec: Long, val kind: LightEvent.Kind)

    /** Positions as the board stores them (7 decimals), so the same row from two downloads is one. */
    private fun key(e: LightEvent) = Key(Math.round(e.light.latitude * 1e7), Math.round(e.light.longitude * 1e7), e.epochSec, e.kind)
}
