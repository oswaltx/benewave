package dev.greenwave.shared.data

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.model.TrafficLight
import kotlin.random.Random

/**
 * Generates plausible-but-fake signal timing for a traffic light whose real
 * position is known (e.g. from OpenStreetMap) but whose real cycle/green
 * timing isn't published anywhere. A stand-in until real data (city
 * response, self-measurement) is available — see the route-creation design.
 */
object RandomTrafficTimingGenerator {
    fun generate(position: LatLng, random: Random = Random.Default): TrafficLight {
        val cycleLengthSec = random.nextInt(45, 91)
        val greenDurationSec = random.nextInt(15, 31).coerceAtMost(cycleLengthSec - 1)
        val greenStartOffsetSec = random.nextInt(0, cycleLengthSec)

        return TrafficLight(
            position = position,
            cycleLengthSec = cycleLengthSec,
            greenStartOffsetSec = greenStartOffsetSec,
            greenDurationSec = greenDurationSec,
        )
    }

    /**
     * Deterministic version: same position always yields the same fake
     * timings, so saving the same route twice doesn't randomly shuffle
     * the traffic-light programs the optimizer works against. Without
     * this, every `RouteRepository.save(...)` produces a different
     * `Route` object (same path, different `trafficLights`) and the
     * measured-rhythm overlay never matches anything — there's no stable
     * baseline to detect improvement against.
     *
     * The seed is derived from a stable stringification of the position,
     * so this is the same function whether you're on the JVM or any other
     * Kotlin Multiplatform target.
     */
    fun deterministic(position: LatLng): TrafficLight {
        val seed = (position.latitude * 1e6).toRawBits() xor
            (position.longitude * 1e6).toRawBits().rotateLeft(13)
        return generate(position, Random(seed))
    }
}
