package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.LightEvent
import kotlin.random.Random

/**
 * Picks what of one ride may be shared with other riders: only light events
 * farther than a per-ride random radius from the ride's first and last fix, so
 * the shared events do not point at where the rider lives or goes. The radius
 * is drawn anew for each end of each ride (default 400–600 m), so the gap
 * around a home cannot be measured from many rides. Own learning keeps
 * everything; this only filters what leaves the device.
 */
class ShareTrim(
    random: Random = Random.Default,
    minMeters: Double = DEFAULT_MIN_METERS,
    maxMeters: Double = DEFAULT_MAX_METERS,
) {
    init {
        require(minMeters > 0 && maxMeters >= minMeters) { "invalid radius $minMeters..$maxMeters" }
    }

    val startRadiusMeters: Double = random.nextDouble(minMeters, maxMeters + 1e-9)
    val endRadiusMeters: Double = random.nextDouble(minMeters, maxMeters + 1e-9)

    private var first: LatLng? = null
    private var last: LatLng? = null
    private val events = mutableListOf<LightEvent>()

    /** Every fix of the ride. */
    fun onFix(position: LatLng) {
        if (first == null) first = position
        last = position
    }

    /** Events stored for own learning during this ride. */
    fun add(stored: Collection<LightEvent>) {
        events.addAll(stored)
    }

    /** End of ride: the events that may be shared; the trim starts afresh. */
    fun end(): List<LightEvent> {
        val start = first
        val end = last
        val shareable = if (start == null || end == null) emptyList()
        else events.filter {
            distanceMeters(it.light, start) > startRadiusMeters && distanceMeters(it.light, end) > endRadiusMeters
        }
        events.clear()
        first = null
        last = null
        return shareable
    }

    companion object {
        const val DEFAULT_MIN_METERS = 400.0
        const val DEFAULT_MAX_METERS = 600.0
    }
}
