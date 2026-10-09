package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.location.LocationUpdate
import kotlin.math.roundToInt

/**
 * Notices riding through a traffic light without stopping — evidence that it
 * was green at that moment. For each light the rider comes within
 * [radiusMeters] of, it keeps the closest fix; once the rider has left the
 * radius again and never dropped below [minSpeedKmh] on the way, it reports a
 * [LightEvent.Kind.PASS_GREEN] at the time of the closest fix.
 */
class PassDetector(
    private val radiusMeters: Double = 20.0,
    private val minSpeedKmh: Double = 10.0,
) {
    private class Approach(var closest: Double, var epochSec: Long, var heading: Float?, var minSpeed: Double)

    private val approaches = mutableMapOf<LatLng, Approach>()

    /** [nearbyLights]: the lights worth checking for this fix (caller pre-filters, e.g. within 100 m). */
    fun onUpdate(update: LocationUpdate, nearbyLights: List<LatLng>): List<LightEvent> {
        val events = mutableListOf<LightEvent>()
        for (light in nearbyLights) {
            val d = distanceMeters(update.position, light)
            val approach = approaches[light]
            if (d <= radiusMeters) {
                if (approach == null) {
                    approaches[light] = Approach(d, update.epochSec, update.bearingDegrees, update.speedKmh)
                } else {
                    if (d < approach.closest) {
                        approach.closest = d
                        approach.epochSec = update.epochSec
                        approach.heading = update.bearingDegrees ?: approach.heading
                    }
                    approach.minSpeed = minOf(approach.minSpeed, update.speedKmh)
                }
            }
        }
        // Finish every approach the rider has left (outside the radius or no longer nearby).
        val iterator = approaches.iterator()
        while (iterator.hasNext()) {
            val (light, approach) = iterator.next()
            val stillInside = light in nearbyLights && distanceMeters(update.position, light) <= radiusMeters
            if (stillInside) continue
            iterator.remove()
            if (approach.minSpeed >= minSpeedKmh) {
                events.add(LightEvent(light, approach.epochSec, LightEvent.Kind.PASS_GREEN, approach.heading?.roundToInt()))
            }
        }
        return events
    }
}
