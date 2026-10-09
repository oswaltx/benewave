package dev.greenwave.shared.guidance

import dev.greenwave.shared.algorithm.Intersections
import dev.greenwave.shared.algorithm.SpeedOptions
import dev.greenwave.shared.algorithm.SpeedOptionsCalculator
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.tracking.RhythmEstimate

/**
 * Speed advice along a route, shared by Android and iOS. Holds the hardest
 * invariant of greenWave: a speed only comes from lights whose program is
 * learned (confidence ≥ [MIN_CONFIDENCE]) or known live. Unknown lights get a
 * placeholder program ([RandomTrafficTimingGenerator]) so the route stays
 * complete, but [resolve] leaves them out of [Resolved.known] and [advice]
 * never lets them steer the speed – neither the next light nor one further on.
 */
object RouteAdvice {
    /** Measured rhythms below this confidence are not trusted over the placeholder. */
    const val MIN_CONFIDENCE = 0.5

    /** Only lights this far ahead count for the advice; farther ones are too uncertain. */
    const val LOOKAHEAD_METERS = 2_000.0

    /** Lights in route order with their program, and which of them are known (live or learned). */
    class Resolved(val lights: List<TrafficLight>, val known: Set<LatLng>)

    /**
     * What the panel and notification show for the next light. Without a learned
     * program for it ([nextLightKnown] false) there is deliberately no speed.
     */
    data class Advice(
        val nextLightDistanceMeters: Double?,
        val nextLightKnown: Boolean,
        /** Null if there is no light ahead or it is not known. */
        val options: SpeedOptions?,
    )

    /**
     * Programs for [positions]: the live one if [live] has it, else the learned
     * one from [estimate] if confident enough, else the placeholder.
     */
    fun resolve(
        positions: List<LatLng>,
        live: Map<LatLng, TrafficLight>,
        estimate: (LatLng) -> RhythmEstimate?,
    ): Resolved {
        val known = mutableSetOf<LatLng>()
        val lights = positions.map { position ->
            live[position]?.let {
                known.add(position)
                return@map it
            }
            val e = estimate(position)
            if (e != null && e.confidence >= MIN_CONFIDENCE) {
                known.add(position)
                TrafficLight(
                    position = position,
                    cycleLengthSec = e.cycleLengthSec,
                    greenStartOffsetSec = e.greenStartOffsetSec,
                    greenDurationSec = e.greenDurationSec,
                )
            } else {
                RandomTrafficTimingGenerator.deterministic(position)
            }
        }
        return Resolved(lights, known)
    }

    /** Advice for [update] on [route], whose lights in [known] are live or learned. */
    fun advice(
        route: Route,
        known: Set<LatLng>,
        update: LocationUpdate,
        speedRange: SpeedRangeKmh,
        lookaheadMeters: Double = LOOKAHEAD_METERS,
    ): Advice {
        val progress = distanceAlongPath(route.path, update.position)
        // One light per intersection, the known one if there is any (see Intersections).
        val ahead = Intersections.representatives(
            route.trafficLights
                .map { it to distanceAlongPath(route.path, it.position) - progress }
                .filter { it.second > 0 && it.second <= lookaheadMeters }
                .sortedBy { it.second },
            isKnown = { it.position in known },
        )
        val next = ahead.firstOrNull() ?: return Advice(null, false, null)
        val nextKnown = next.first.position in known
        // Only known lights take part; an unknown one further on must not steer the speed either.
        val knownAhead = ahead.map { it.first }.takeWhile { it.position in known }
        val options = if (!nextKnown) null else SpeedOptionsCalculator.compute(
            route = route.copy(trafficLights = knownAhead),
            currentPosition = update.position,
            nowEpochSec = update.epochSec,
            currentSpeedKmh = update.speedKmh,
            speedRange = speedRange,
        )
        return Advice(next.second, nextKnown, options)
    }
}
