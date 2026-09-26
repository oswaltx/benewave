package dev.greenwave.shared.guidance

import dev.greenwave.shared.algorithm.GreenWaveOptimizer
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route

/**
 * The handful of numbers a compact, glanceable display (floating overlay,
 * OLED mode) needs — a strict subset of what the full Tacho screen shows.
 */
data class RideGuidance(
    val targetSpeedKmh: Double?,
    val currentSpeedKmh: Double,
    val advice: SpeedAdvice,
    val nextLightDistanceMeters: Double?,
)

/**
 * Computes [RideGuidance] from one GPS fix without any Android or
 * ViewModel dependency, so the background `RideTrackingService`-side
 * overlay can produce the same recommendation the Tacho screen shows.
 */
object RideGuidanceCalculator {

    fun compute(
        route: Route,
        update: LocationUpdate,
        speedRange: SpeedRangeKmh,
        toleranceKmh: Double = SpeedAdvisor.DEFAULT_TOLERANCE_KMH,
    ): RideGuidance {
        if (route.path.size < 2) {
            return RideGuidance(
                targetSpeedKmh = null,
                currentSpeedKmh = update.speedKmh,
                advice = SpeedAdvice.NONE,
                nextLightDistanceMeters = null,
            )
        }

        val recommendation = GreenWaveOptimizer.recommend(
            route = route,
            currentPosition = update.position,
            nowEpochSec = update.epochSec,
            speedRange = speedRange,
        )

        val currentProgress = distanceAlongPath(route.path, update.position)
        val nextLightDistance = route.trafficLights
            .map { distanceAlongPath(route.path, it.position) - currentProgress }
            .filter { it > 0.0 }
            .minOrNull()

        val target = recommendation?.speedKmh
        return RideGuidance(
            targetSpeedKmh = target,
            currentSpeedKmh = update.speedKmh,
            advice = SpeedAdvisor.advise(update.speedKmh, target, toleranceKmh),
            nextLightDistanceMeters = nextLightDistance,
        )
    }
}
