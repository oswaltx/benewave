package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters

/**
 * Matches an observed [StopEvent] to the nearest known traffic-light
 * position, if it falls inside [maxRadiusMeters]. Returns `null` when
 * the stop is too far from any known Ampel — the rider probably paused
 * for a reason other than a signal (Café, Bahnübergang, Vorfahrt), and
 * including those in the rhythm estimate would corrupt it.
 *
 * Pure: no I/O, no coroutines, safe to call on the dispatcher's hot path.
 */
object StopEventMatcher {

    /**
     * @param candidatePositions positions of traffic lights, in any order.
     *   Usually `route.trafficLights.map { it.position }` for the active
     *   ride. Empty list returns `null`.
     * @return the [LatLng] of the nearest candidate within
     *   [maxRadiusMeters], or `null` if none qualifies.
     */
    fun match(
        eventPosition: LatLng,
        candidatePositions: List<LatLng>,
        maxRadiusMeters: Double = DEFAULT_MAX_RADIUS_METERS,
    ): LatLng? {
        if (candidatePositions.isEmpty()) return null

        var bestPosition: LatLng? = null
        var bestDistance = maxRadiusMeters
        for (candidate in candidatePositions) {
            val distance = distanceMeters(eventPosition, candidate)
            if (distance <= bestDistance) {
                bestDistance = distance
                bestPosition = candidate
            }
        }
        return bestPosition
    }

    /**
     * Default radius — generously larger than a cyclist at the stop line
     * (GPS drift of 5-10 m while standing is normal, see docs/stop-events.md)
     * but small enough that a stop around a corner doesn't get attributed to
     * the intersection that just happens to be the closest known node.
     */
    const val DEFAULT_MAX_RADIUS_METERS: Double = 30.0
}