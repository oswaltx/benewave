package dev.greenwave.shared.algorithm

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.math.abs

data class SpeedRangeKmh(val min: Double, val max: Double)

data class SpeedRecommendation(
    val speedKmh: Double,
    val lightsHitGreen: Int,
    val totalRemainingLights: Int,
)

/**
 * Finds a single constant speed that gets the rider through as many of the
 * route's remaining traffic lights on green as possible.
 *
 * This is a brute-force search over [speedRange] in [stepKmh] steps: for
 * each candidate speed, simulate the arrival time at every remaining light
 * and count how many fall inside that light's green phase. The search
 * space is tiny (a few hundred speed steps times a few dozen lights), so
 * brute force runs in well under a millisecond — deliberately chosen over
 * a cleverer algorithm because it stays easy to read and to test.
 */
object GreenWaveOptimizer {

    fun recommend(
        route: Route,
        currentPosition: LatLng,
        nowEpochSec: Long,
        speedRange: SpeedRangeKmh,
        stepKmh: Double = 0.5,
    ): SpeedRecommendation? {
        val currentProgress = distanceAlongPath(route.path, currentPosition)

        val remainingLights = route.trafficLights
            .map { light -> light to distanceAlongPath(route.path, light.position) }
            .filter { (_, progress) -> progress > currentProgress }

        if (remainingLights.isEmpty()) return null

        var bestSpeed = speedRange.min
        var bestHits = -1

        var speed = speedRange.min
        while (speed <= speedRange.max + 1e-9) {
            val hits = remainingLights.count { (light, progress) ->
                val distanceToLight = progress - currentProgress
                isGreenAt(light, arrivalTimeEpochSec(nowEpochSec, distanceToLight, speed))
            }

            if (hits > bestHits || (hits == bestHits && isCloserToRangeMiddle(speed, bestSpeed, speedRange))) {
                bestHits = hits
                bestSpeed = speed
            }

            speed += stepKmh
        }

        return SpeedRecommendation(
            speedKmh = bestSpeed,
            lightsHitGreen = bestHits,
            totalRemainingLights = remainingLights.size,
        )
    }

    private fun arrivalTimeEpochSec(nowEpochSec: Long, distanceMeters: Double, speedKmh: Double): Long {
        val speedMetersPerSec = speedKmh * 1000.0 / 3600.0
        val secondsToArrival = distanceMeters / speedMetersPerSec
        return nowEpochSec + secondsToArrival.toLong()
    }

    private fun isGreenAt(light: TrafficLight, epochSec: Long): Boolean {
        val secondsSinceGreenStart = epochSec - light.greenStartOffsetSec
        val positionInCycle = ((secondsSinceGreenStart % light.cycleLengthSec) + light.cycleLengthSec) % light.cycleLengthSec
        return positionInCycle < light.greenDurationSec
    }

    private fun isCloserToRangeMiddle(candidate: Double, current: Double, range: SpeedRangeKmh): Boolean {
        val middle = (range.min + range.max) / 2.0
        return abs(candidate - middle) < abs(current - middle)
    }
}
