package dev.greenwave.shared.algorithm

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceAlongPath
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.math.abs

/**
 * The two ways to make the next light on green: speed up to catch an earlier
 * green, or slow down to arrive in a later one. [holdKmh] is set when the
 * current speed already arrives on green.
 */
data class SpeedOptions(
    val slowerKmh: Double?,
    val fasterKmh: Double?,
    val holdKmh: Double?,
)

/**
 * For the next light ahead, the speeds in [SpeedRangeKmh] that arrive on green
 * form bands (one per green phase). The nearest band above the current speed
 * is the faster option, the nearest band below it the slower one. Within a
 * band the speed that also gets the most later lights on green wins, ties go
 * to the speed closest to the current one (least effort).
 *
 * Arrivals within [startMarginSec] after green starts or [endMarginSec] before
 * it ends don't count, so the options don't sit right on a phase edge.
 */
object SpeedOptionsCalculator {

    fun compute(
        route: Route,
        currentPosition: LatLng,
        nowEpochSec: Long,
        currentSpeedKmh: Double,
        speedRange: SpeedRangeKmh,
        stepKmh: Double = 0.5,
        startMarginSec: Int = 2,
        endMarginSec: Int = 3,
    ): SpeedOptions? {
        val progress = distanceAlongPath(route.path, currentPosition)
        val ahead = route.trafficLights
            .map { it to distanceAlongPath(route.path, it.position) - progress }
            .filter { it.second > 0.0 }
            .sortedBy { it.second }
        if (ahead.isEmpty()) return null
        val (next, nextDistance) = ahead.first()

        class Candidate(val speed: Double, val hits: Int)
        val bands = mutableListOf<MutableList<Candidate>>()
        var inBand = false
        var speed = speedRange.min
        while (speed <= speedRange.max + 1e-9) {
            if (arrivesOnGreen(next, nowEpochSec, nextDistance, speed, startMarginSec, endMarginSec)) {
                val hits = ahead.count { (light, d) -> arrivesOnGreen(light, nowEpochSec, d, speed, 0, 0) }
                if (!inBand) bands.add(mutableListOf())
                bands.last().add(Candidate(speed, hits))
                inBand = true
            } else {
                inBand = false
            }
            speed += stepKmh
        }

        fun best(band: List<Candidate>): Double = band
            .sortedWith(compareByDescending<Candidate> { it.hits }.thenBy { abs(it.speed - currentSpeedKmh) })
            .first().speed

        val current = bands.firstOrNull { band ->
            currentSpeedKmh >= band.first().speed - stepKmh / 2 && currentSpeedKmh <= band.last().speed + stepKmh / 2
        }
        val faster = bands.firstOrNull { it.first().speed > currentSpeedKmh && it !== current }
        val slower = bands.lastOrNull { it.last().speed < currentSpeedKmh && it !== current }
        return SpeedOptions(
            slowerKmh = slower?.let(::best),
            fasterKmh = faster?.let(::best),
            holdKmh = current?.let(::best),
        )
    }

    private fun arrivesOnGreen(
        light: TrafficLight,
        nowEpochSec: Long,
        distanceMeters: Double,
        speedKmh: Double,
        startMarginSec: Int,
        endMarginSec: Int,
    ): Boolean {
        val arrival = nowEpochSec + (distanceMeters / (speedKmh / 3.6)).toLong()
        val c = light.cycleLengthSec
        val pos = (((arrival - light.greenStartOffsetSec) % c) + c) % c
        return pos >= startMarginSec && pos < light.greenDurationSec - endMarginSec
    }
}
