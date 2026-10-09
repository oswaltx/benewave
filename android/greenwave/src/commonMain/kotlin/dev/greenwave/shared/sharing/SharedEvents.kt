package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.degToRad
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.LightEvent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.floor

/**
 * One cell of the board's fixed grid ([SharedEvents.TILE_DEG] per side, south-west
 * corner at [latIdx] * TILE_DEG, [lonIdx] * TILE_DEG). Downloads ask for whole
 * cells only, so a request does not reveal where exactly someone rides.
 */
data class Tile(val latIdx: Int, val lonIdx: Int)

/**
 * A downloaded event and its [source] on the board (one device in one calendar
 * week). The board renames sources in every answer, so a name only means the
 * same source within one [scope] (one tile's download).
 */
data class SourcedEvent(val event: LightEvent, val source: String, val scope: String = "")

/** Helpers for the events other riders shared. */
object SharedEvents {
    /** Shared events older than this are of no use for learning (the estimator looks back 28 days). */
    const val MAX_AGE_SEC = 60L * 86_400

    /** Only events younger than this leave the device, so the board (which takes 60 days) never rejects them. */
    const val MAX_UPLOAD_AGE_SEC = 55L * 86_400

    /** Events stamped further in the future (wrong clock) are not sent (and dropped from the queue). */
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

    /** Side of a board tile in degrees (same as TILE_DEG in pinnwand.py). */
    const val TILE_DEG = 0.25

    /** At most this many tiles per download; a 30 km radius needs about 12 at German latitudes. */
    const val MAX_TILES = 16

    /** Shared events of a light count only when this many independent sources reported it. */
    const val MIN_SOURCES = 2

    /** At most this many events of one source per light (one rider passes a light a few times a day). */
    const val MAX_PER_SOURCE_AND_LIGHT = 30

    /** Speed above which two own events cannot both be real (GPS or clock trouble); the board rejects such uploads. */
    const val MAX_UPLOAD_SPEED_MPS = 20.0

    /** Below this distance two events are not compared for speed (lights of one junction, position noise). */
    const val SPEED_CHECK_METERS = 30.0

    /** How far back [plausible] compares an event with all kept ones. */
    const val SPEED_CHECK_WINDOW_SEC = 600L

    fun tileOf(position: LatLng) = Tile(
        floor(position.latitude / TILE_DEG).toInt(),
        floor(position.longitude / TILE_DEG).toInt(),
    )

    /**
     * The tiles to fetch shared events for: those around the rider's own lights
     * within [radiusMeters] of the most recent one (so a trip to another city does
     * not blow up the area), each light widened by [marginMeters]; nearest to the
     * most recent light first, at most [maxTiles]. Empty without own events.
     */
    fun tiles(
        own: List<LightEvent>,
        radiusMeters: Double = 30_000.0,
        marginMeters: Double = 2_000.0,
        maxTiles: Int = MAX_TILES,
    ): List<Tile> {
        val latest = own.maxByOrNull { it.epochSec }?.light ?: return emptyList()
        val dLat = marginMeters / 111_320.0
        val dLon = marginMeters / (111_320.0 * cos(degToRad(latest.latitude)).coerceAtLeast(0.1))
        val tiles = HashSet<Tile>()
        for (light in own.map { it.light }.distinct()) {
            if (distanceMeters(light, latest) > radiusMeters) continue
            for (lat in listOf(light.latitude - dLat, light.latitude, light.latitude + dLat))
                for (lon in listOf(light.longitude - dLon, light.longitude, light.longitude + dLon))
                    tiles.add(tileOf(LatLng(lat, lon)))
        }
        fun center(t: Tile) = LatLng((t.latIdx + 0.5) * TILE_DEG, (t.lonIdx + 0.5) * TILE_DEG)
        return tiles.sortedBy { distanceMeters(center(it), latest) }.take(maxTiles)
    }

    /**
     * Of [downloaded], the events worth learning from. A forged light needs more
     * than one device: events of a light count only if at least [MIN_SOURCES]
     * sources reported it (lights matched within [MATCH_METERS]), and each source
     * contributes at most its [MAX_PER_SOURCE_AND_LIGHT] newest events there.
     * The rider's [own] events (they come back from the board) are no source.
     * Sources are counted within one scope only (the board names them per tile
     * download and ~100 m cell): a light at an edge must not count one device
     * twice because it was named differently on both sides.
     */
    fun credible(downloaded: List<SourcedEvent>, own: List<LightEvent>): List<LightEvent> {
        // The rider's own events come back from the board; they must not count as a source, or one forger would be enough.
        val ownByMoment = own.groupBy { it.epochSec to it.kind }
        val others = downloaded.filter { !isOwn(it.event, ownByMoment) }
        return credibleOf(others)
    }

    private fun isOwn(e: LightEvent, ownByMoment: Map<Pair<Long, LightEvent.Kind>, List<LightEvent>>) =
        ownByMoment[e.epochSec to e.kind].orEmpty().any { distanceMeters(it.light, e.light) <= MATCH_METERS }

    private fun credibleOf(downloaded: List<SourcedEvent>): List<LightEvent> {
        // Cluster lights within MATCH_METERS via a grid of ~ MATCH_METERS cells and their neighbours.
        val cell = MATCH_METERS / 111_320.0
        fun cellOf(p: LatLng) = floor(p.latitude / cell).toLong() to floor(p.longitude / cell).toLong()
        val positions = downloaded.map { it.event.light }.distinct()
        val grid = positions.groupBy(::cellOf)
        val clusterOf = HashMap<LatLng, Int>()
        var next = 0
        for (p in positions) {
            if (p in clusterOf) continue
            val id = next++
            val queue = ArrayDeque(listOf(p))
            clusterOf[p] = id
            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                val (cy, cx) = cellOf(q)
                // Longitude cells are narrower than MATCH_METERS away from the equator, so look further east/west.
                val span = (1 / cos(degToRad(q.latitude)).coerceAtLeast(0.1)).toInt() + 1
                for (y in cy - 1..cy + 1) for (x in cx - span..cx + span) {
                    for (r in grid[y to x].orEmpty()) {
                        if (r !in clusterOf && distanceMeters(q, r) <= MATCH_METERS) {
                            clusterOf[r] = id
                            queue.add(r)
                        }
                    }
                }
            }
        }
        return downloaded.groupBy { clusterOf.getValue(it.event.light) }.values
            .filter { atLight -> atLight.groupBy { it.scope }.values.any { s -> s.map { it.source }.distinct().size >= MIN_SOURCES } }
            .flatMap { atLight ->
                atLight.groupBy { it.scope to it.source }.values.flatMap { bySource ->
                    bySource.sortedByDescending { it.event.epochSec }.take(MAX_PER_SOURCE_AND_LIGHT).map { it.event }
                }
            }
            .sortedBy { it.epochSec }
    }

    /**
     * Of the rider's own [events], those that fit one rider on a bike: in time
     * order, an event is dropped if it is further than [SPEED_CHECK_METERS] from
     * a kept one of the last [SPEED_CHECK_WINDOW_SEC] (or the last kept one) and
     * reached faster than [MAX_UPLOAD_SPEED_MPS] (GPS jump, clock trouble). The
     * board rejects uploads that are not plausible (with more slack, so in
     * practice any chunk of what this keeps passes), and one bad event must not
     * cost the whole week. A single outlier (too fast from both neighbours,
     * which fit each other) goes first, so it cannot knock out the real events
     * after it.
     */
    fun plausible(events: List<LightEvent>): List<LightEvent> {
        val sorted = events.sortedBy { it.epochSec }
        val withoutOutliers = sorted.filterIndexed { i, e ->
            val before = sorted.getOrNull(i - 1)
            val after = sorted.getOrNull(i + 1)
            when {
                before != null && after != null -> fits(before, e) || fits(e, after) || !fits(before, after)
                // At the ends: an outlier if it does not fit its neighbour while the neighbour fits the next one.
                after != null -> fits(e, after) || sorted.getOrNull(i + 2)?.let { !fits(after, it) } ?: true
                before != null -> fits(before, e) || sorted.getOrNull(i - 2)?.let { !fits(it, before) } ?: true
                else -> true
            }
        }
        val kept = ArrayList<LightEvent>()
        for (e in withoutOutliers) {
            val recent = kept.asReversed().asSequence().takeWhile { e.epochSec - it.epochSec <= SPEED_CHECK_WINDOW_SEC }
            if ((recent + listOfNotNull(kept.lastOrNull())).all { k -> fits(k, e) }) kept.add(e)
        }
        return kept
    }

    private fun fits(a: LightEvent, b: LightEvent): Boolean {
        val d = distanceMeters(a.light, b.light)
        return d <= SPEED_CHECK_METERS || d / max(abs(b.epochSec - a.epochSec), 1L) <= MAX_UPLOAD_SPEED_MPS
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
                    !isOwn(e, ownByMoment) &&
                    seen.add(key(e))
            }
            .sortedBy { it.epochSec }
    }

    private data class Key(val lat: Long, val lon: Long, val epochSec: Long, val kind: LightEvent.Kind)

    /** Positions as the board stores them (7 decimals), so the same row from two downloads is one. */
    private fun key(e: LightEvent) = Key((e.light.latitude * 1e7).roundToLong(), (e.light.longitude * 1e7).roundToLong(), e.epochSec, e.kind)
}
