package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.bearingDegrees
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.location.LocationUpdate
import kotlin.math.roundToInt

/**
 * Holds back a light's events until it is clear which way the rider went.
 *
 * At a junction, straight on and turning left often have different green
 * phases, and that only shows once the rider has left: the events of a stop or
 * pass-through are released when the rider is [exitDistanceMeters] past the
 * light, with the approach and exit direction measured from positions
 * (approach: the last fix [exitDistanceMeters] before the light, exit: the
 * first one that far after it). Positions, not the GPS bearing, because that is
 * unreliable while standing at a red light.
 *
 * Creeping forward in a queue at one red light shows up as several stops. A
 * stop that begins before the rider has got past the previous stop's light
 * (within [queueRadiusMeters] of it) is the same wait: red started with the
 * first stop, green with the end of the last one. Only that pair is released.
 *
 * Events whose exit never resolves (ride ended, GPS lost) are released without
 * exit after [maxPendingSec], or by [flush].
 */
class MovementResolver(
    private val exitDistanceMeters: Double = 40.0,
    private val queueRadiusMeters: Double = 60.0,
    private val maxPendingSec: Long = 300,
) {
    private class Pending(
        var light: LatLng,
        var events: List<LightEvent>,
        /** When the rider left the light: the pass time, or the end of the (merged) stop. */
        var departedEpochSec: Long,
        val isStop: Boolean,
    )

    private val pending = mutableListOf<Pending>()
    private val history = ArrayDeque<LocationUpdate>()

    /** A stop at [light]; merged into a still pending stop of the same wait if there is one. */
    fun addStop(stop: StopEvent, light: LatLng) {
        val events = LightEvent.fromStop(stop, light, null)
        val sameWait = pending.lastOrNull { p ->
            p.isStop && distanceMeters(p.light, stop.position) <= queueRadiusMeters &&
                stop.startEpochSec - p.departedEpochSec <= maxPendingSec
        }
        if (sameWait != null) {
            val redFrom = sameWait.events.first { it.kind == LightEvent.Kind.STOP_START }
            sameWait.light = light
            sameWait.events = listOf(redFrom.copy(light = light), events.last())
            sameWait.departedEpochSec = stop.endEpochSec
        } else {
            pending.add(Pending(light, events, stop.endEpochSec, isStop = true))
        }
    }

    /** A pass-through on green (from [PassDetector]). */
    fun addPass(event: LightEvent) {
        pending.add(Pending(event.light, listOf(event), event.epochSec, isStop = false))
    }

    /** Every fix; returns the events whose direction is now known (or that timed out). */
    fun onUpdate(update: LocationUpdate): List<LightEvent> {
        history.addLast(update)
        while (history.isNotEmpty() && update.epochSec - history.first().epochSec > maxPendingSec + 60) {
            history.removeFirst()
        }
        val released = mutableListOf<LightEvent>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val exitFix = history.firstOrNull {
                it.epochSec >= p.departedEpochSec && distanceMeters(p.light, it.position) >= exitDistanceMeters
            }
            val timedOut = update.epochSec - p.departedEpochSec > maxPendingSec
            if (exitFix == null && !timedOut) continue
            iterator.remove()
            released.addAll(resolve(p, exitFix))
        }
        return released
    }

    /** Releases everything still pending (end of ride), with exit where it can be told. */
    fun flush(): List<LightEvent> {
        val released = pending.flatMap { p ->
            resolve(p, history.firstOrNull {
                it.epochSec >= p.departedEpochSec && distanceMeters(p.light, it.position) >= exitDistanceMeters
            })
        }
        pending.clear()
        history.clear()
        return released
    }

    private fun resolve(p: Pending, exitFix: LocationUpdate?): List<LightEvent> {
        val arrived = p.events.first().epochSec
        val approachFix = history.lastOrNull {
            it.epochSec <= arrived && distanceMeters(it.position, p.light) >= exitDistanceMeters
        }
        val approach = approachFix?.let { bearingDegrees(it.position, p.light).roundToInt() % 360 }
        val exit = exitFix?.let { bearingDegrees(p.light, it.position).roundToInt() % 360 }
        return p.events.map { e ->
            e.copy(headingDeg = approach ?: e.headingDeg, exitHeadingDeg = exit)
        }
    }
}
