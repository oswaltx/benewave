package dev.greenwave.shared.navigation

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.geo.pointAtDistanceAlongPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

enum class TurnDirection {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,

    /** End of the route. */
    ARRIVE,
}

/**
 * A turn at [position], [distanceAlongPathMeters] from the route start
 * (same measure as `distanceAlongPath`, so it compares directly against the
 * rider's progress). [turnAngleDegrees] is signed: positive = right
 * (clockwise), negative = left, in (-180, 180].
 */
data class Maneuver(
    val distanceAlongPathMeters: Double,
    val position: LatLng,
    val turnAngleDegrees: Double,
    val direction: TurnDirection,
)

/** What the OLED screen shows: the next thing to do and how far away it is. */
data class UpcomingManeuver(
    val direction: TurnDirection,
    val turnAngleDegrees: Double,
    val distanceMeters: Double,
)

/**
 * Derives turn-by-turn maneuvers from the route geometry alone.
 *
 * Why geometry instead of the router's instructions: the stored [Route]
 * (`dev.greenwave.shared.model.Route`) only keeps the polyline, routes
 * saved before this feature must keep working, and it stays independent of
 * GraphHopper's instruction format. GraphHopper *does* return
 * `instructions` with a `sign` per step; switching to those later would
 * mean extending the stored route model.
 *
 * How: at every interior vertex, compare the heading over the
 * [LOOKAROUND_METERS] before it with the heading over the
 * [LOOKAROUND_METERS] after it. Measuring over a distance rather than just
 * the adjacent segments means a smooth curve drawn with many short
 * segments (each only a few degrees) still adds up to one real turn, and
 * GPS-ish zigzags of a metre don't. Vertices whose turn is at least
 * [MIN_TURN_DEGREES] become candidates; candidates within
 * [MERGE_DISTANCE_METERS] of each other are one turn, represented by the
 * sharpest of them.
 */
object ManeuverDeriver {

    /** Below this the road merely bends — no arrow needed. */
    const val MIN_TURN_DEGREES = 30.0
    const val SLIGHT_LIMIT_DEGREES = 60.0
    const val SHARP_LIMIT_DEGREES = 135.0
    const val LOOKAROUND_METERS = 20.0
    const val MERGE_DISTANCE_METERS = 25.0

    /** Beyond this distance the display says "geradeaus" instead of the far-off turn. */
    const val STRAIGHT_BEYOND_METERS = 400.0

    private const val METERS_PER_DEGREE = 111_320.0

    fun derive(path: List<LatLng>): List<Maneuver> {
        if (path.size < 2) return emptyList()

        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(path[i - 1], path[i])
        }
        val total = cumulative.last()

        val candidates = mutableListOf<Maneuver>()
        for (i in 1 until path.size - 1) {
            val vertex = path[i]
            val before = pointAtDistanceAlongPath(path, cumulative[i] - LOOKAROUND_METERS)
            val after = pointAtDistanceAlongPath(path, cumulative[i] + LOOKAROUND_METERS)
            if (distanceMeters(before, vertex) < 1.0 || distanceMeters(vertex, after) < 1.0) continue

            val turn = normalizeDegrees(bearingDegrees(vertex, after) - bearingDegrees(before, vertex))
            if (abs(turn) < MIN_TURN_DEGREES) continue
            candidates += Maneuver(cumulative[i], vertex, turn, classify(turn))
        }

        val merged = mutableListOf<Maneuver>()
        for (candidate in candidates) {
            val last = merged.lastOrNull()
            if (last != null &&
                candidate.distanceAlongPathMeters - last.distanceAlongPathMeters <= MERGE_DISTANCE_METERS &&
                // A left right after a right is a real zigzag, not one turn.
                (candidate.turnAngleDegrees > 0) == (last.turnAngleDegrees > 0)
            ) {
                if (abs(candidate.turnAngleDegrees) > abs(last.turnAngleDegrees)) {
                    merged[merged.lastIndex] = candidate
                }
            } else {
                merged += candidate
            }
        }

        merged += Maneuver(total, path.last(), 0.0, TurnDirection.ARRIVE)
        return merged
    }

    fun classify(turnDegrees: Double): TurnDirection {
        val magnitude = abs(turnDegrees)
        val right = turnDegrees > 0
        return when {
            magnitude < MIN_TURN_DEGREES -> TurnDirection.STRAIGHT
            magnitude < SLIGHT_LIMIT_DEGREES -> if (right) TurnDirection.SLIGHT_RIGHT else TurnDirection.SLIGHT_LEFT
            magnitude < SHARP_LIMIT_DEGREES -> if (right) TurnDirection.RIGHT else TurnDirection.LEFT
            else -> if (right) TurnDirection.SHARP_RIGHT else TurnDirection.SHARP_LEFT
        }
    }

    /**
     * The next maneuver strictly ahead of [progressMeters] (from
     * `distanceAlongPath`). If it is further than [straightBeyondMeters]
     * away, reports [TurnDirection.STRAIGHT] with the distance to it — "keep
     * going for 1.2 km" is more useful than an arrow for a turn you can't
     * see yet. Null only if [maneuvers] is empty or everything is behind.
     */
    fun upcoming(
        maneuvers: List<Maneuver>,
        progressMeters: Double,
        straightBeyondMeters: Double = STRAIGHT_BEYOND_METERS,
    ): UpcomingManeuver? {
        val next = maneuvers.firstOrNull { it.distanceAlongPathMeters > progressMeters } ?: return null
        val distance = next.distanceAlongPathMeters - progressMeters
        return if (distance > straightBeyondMeters) {
            UpcomingManeuver(TurnDirection.STRAIGHT, 0.0, distance)
        } else {
            UpcomingManeuver(next.direction, next.turnAngleDegrees, distance)
        }
    }

    /** Compass bearing from [from] to [to], degrees clockwise from north, flat-earth approximation. */
    internal fun bearingDegrees(from: LatLng, to: LatLng): Double {
        val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(from.latitude * PI / 180.0)
        val dx = (to.longitude - from.longitude) * metersPerDegreeLongitude
        val dy = (to.latitude - from.latitude) * METERS_PER_DEGREE
        return atan2(dx, dy) * 180.0 / PI
    }

    /** Into (-180, 180]. */
    internal fun normalizeDegrees(degrees: Double): Double {
        var d = degrees % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }
}
