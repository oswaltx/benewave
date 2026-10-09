package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.location.LocationUpdate

/**
 * Decides, fix by fix, whether a recording is a bicycle ride worth learning
 * from, and how much GPS it needs. Without it, tracking that is left on keeps
 * "learning" lights from a tram or car and drains the battery indoors.
 *
 * - **Vehicle:** at least [vehicleMinSec] of the last [vehicleWindowSec]
 *   seconds above [vehicleKmh] — no learning, until [vehicleCooldownSec]
 *   after the last such stretch.
 * - **Standing still:** below [stillKmh] and within [stillRadiusMeters] for
 *   [stillAfterSec] — no learning, GPS off until the phone moves again
 *   ([Gps.WAIT_FOR_MOTION]). After [endAfterSec] of it the recording ends.
 * - **No fix:** none for [noFixAfterSec] (indoors, tunnel) — GPS only every
 *   now and then ([Gps.SLOW]). After [endAfterSec] without one it ends too.
 *
 * Pure logic on timestamps, no clock of its own: [onFix] for every fix,
 * [onTick] regularly (e.g. every 30 s) so the no-fix and standing-still
 * timers run on while there are no fixes.
 */
class RideGuard(
    private val vehicleKmh: Double = 40.0,
    private val vehicleWindowSec: Long = 20,
    private val vehicleMinSec: Int = 10,
    private val vehicleCooldownSec: Long = 60,
    private val stillKmh: Double = 2.0,
    private val stillRadiusMeters: Double = 30.0,
    private val stillAfterSec: Long = 300,
    private val noFixAfterSec: Long = 120,
    private val endAfterSec: Long = 20 * 60,
) {
    enum class Gps {
        /** Every second: riding. */
        FAST,

        /** Only now and then: no fix for a while. */
        SLOW,

        /** Off until the motion sensor reports movement: standing still for a while. */
        WAIT_FOR_MOTION,
    }

    enum class State { RIDING, VEHICLE, STILL, NO_FIX }

    data class Decision(
        val state: State,
        /** Feed stop/pass detection and the rider profile with this fix. */
        val learn: Boolean,
        val gps: Gps,
        /** The recording should end (long standstill or no fix). */
        val end: Boolean,
    )

    private val recent = ArrayDeque<LocationUpdate>()
    private var lastVehicleSec: Long? = null
    private var anchor: LocationUpdate? = null
    private var lastFixSec: Long? = null
    private var startedSec: Long? = null

    fun onFix(update: LocationUpdate): Decision {
        val t = update.epochSec
        if (startedSec == null) startedSec = t
        lastFixSec = t

        recent.addLast(update)
        while (recent.isNotEmpty() && t - recent.first().epochSec >= vehicleWindowSec) recent.removeFirst()
        if (recent.count { it.speedKmh > vehicleKmh } >= vehicleMinSec) lastVehicleSec = t

        val a = anchor
        if (a == null || update.speedKmh >= stillKmh || distanceMeters(a.position, update.position) > stillRadiusMeters) {
            anchor = update
        }
        return decide(t)
    }

    /** Regular timer; [nowEpochSec] in the same clock as the fixes. */
    fun onTick(nowEpochSec: Long): Decision {
        if (startedSec == null) startedSec = nowEpochSec
        return decide(nowEpochSec)
    }

    /**
     * The motion sensor fired at [nowEpochSec]: movement again, so GPS back on.
     * The standing-still timer restarts with the next fix; the no-fix timer
     * counts from now, as GPS was off on purpose until then.
     */
    fun onMotion(nowEpochSec: Long) {
        anchor = null
        lastFixSec = nowEpochSec
    }

    private fun decide(now: Long): Decision {
        val stillFor = anchor?.let { now - it.epochSec } ?: 0
        val noFixFor = now - (lastFixSec ?: startedSec ?: now)
        val vehicle = lastVehicleSec?.let { now - it <= vehicleCooldownSec } ?: false
        return when {
            stillFor >= stillAfterSec ->
                Decision(State.STILL, learn = false, gps = Gps.WAIT_FOR_MOTION, end = stillFor >= endAfterSec)
            noFixFor >= noFixAfterSec ->
                Decision(State.NO_FIX, learn = false, gps = Gps.SLOW, end = noFixFor >= endAfterSec)
            vehicle -> Decision(State.VEHICLE, learn = false, gps = Gps.FAST, end = false)
            else -> Decision(State.RIDING, learn = true, gps = Gps.FAST, end = false)
        }
    }
}

/**
 * Drops light events near the start and end of a ride: waiting for GPS or
 * unlocking the bike looks like a stop at the nearest light, and these spots
 * are where the rider lives or works. Events within [radiusMeters] of the
 * first fix are dropped; the others are held until the rider has been
 * [radiusMeters] away from them, and whatever is still held when the ride ends
 * is dropped.
 */
class EdgeTrim(private val radiusMeters: Double = 150.0) {
    private var start: LatLng? = null
    private val held = mutableListOf<LightEvent>()

    /** Every fix, with the events released since the last one; returns the events safe to store. */
    fun onUpdate(position: LatLng, released: List<LightEvent>): List<LightEvent> {
        val origin = start ?: position.also { start = it }
        held.addAll(released.filter { distanceMeters(it.light, origin) > radiusMeters })
        val safe = held.filter { distanceMeters(it.light, position) > radiusMeters }
        held.removeAll(safe)
        return safe
    }

    /** End of ride: everything still held is near the end point and is dropped. */
    fun end(): Int = held.size.also {
        held.clear()
        start = null
    }
}
