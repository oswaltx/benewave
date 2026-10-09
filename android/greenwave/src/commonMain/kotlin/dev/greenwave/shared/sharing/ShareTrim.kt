package dev.greenwave.shared.sharing

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.LightEvent
import kotlin.random.Random

/**
 * Picks what of one ride may be shared with other riders: only light events
 * farther than a random radius from every place where the ride began, ended or
 * paused, so the shared events do not point at where the rider lives or goes.
 * The radius is drawn anew for each such place (default 400–600 m), so the gap
 * around a home cannot be measured from many rides. Own learning keeps
 * everything; this only filters what leaves the device.
 *
 * Places that count as an end and a new start:
 * - the first and the last fix of the recording;
 * - a gap without fixes of at least [pauseSec] (GPS lost, phone or app
 *   restarted while the recording went on): the fixes before and after it;
 * - a standstill of at least [pauseSec] within [pauseMeters] (a recording that
 *   runs on while the rider is at home, at work, in a café).
 *
 * Around a begin or end the first and last [edgeSec] of events are dropped as
 * well: after a cold start the first fix can lie hundreds of meters from where
 * the rider set off. If GPS then jumps faster than [jumpSpeedMps] within those
 * first [edgeSec], the fix after the jump counts as a begin too, so the cut also
 * lies around the real start. A restart that loses the recording loses its
 * events too (they are only queued at the end), so nothing of it is shared.
 *
 * A single fix farther than [pauseMeters] from a stay (a GPS outlier) does not
 * end it; leaving takes two fixes in a row outside.
 */
class ShareTrim(
    private val random: Random = Random.Default,
    private val minMeters: Double = DEFAULT_MIN_METERS,
    private val maxMeters: Double = DEFAULT_MAX_METERS,
    private val pauseSec: Long = DEFAULT_PAUSE_SEC,
    private val pauseMeters: Double = DEFAULT_PAUSE_METERS,
    private val edgeSec: Long = DEFAULT_EDGE_SEC,
    private val jumpSpeedMps: Double = DEFAULT_JUMP_SPEED_MPS,
) {
    init {
        require(minMeters > 0 && maxMeters >= minMeters) { "invalid radius $minMeters..$maxMeters" }
    }

    private class Cut(val position: LatLng, val radiusMeters: Double)
    private class Fix(val position: LatLng, val epochSec: Long)

    private val cuts = mutableListOf<Cut>()
    private val quiet = mutableListOf<LongRange>()
    private var last: Fix? = null
    /** Where the rider has stayed (within [pauseMeters]) since when, and whether that already counted as a pause. */
    private var anchor: Fix? = null
    private var anchorCut = false
    /** A first fix outside the stay; the stay ends only if the next fix is outside too. */
    private var outside = false
    /** Until when fixes still settle after a begin (cold start). */
    private var settleUntil = Long.MIN_VALUE
    private val events = mutableListOf<LightEvent>()

    /** The radius of the next begin, end or pause place. */
    private fun radius() = random.nextDouble(minMeters, maxMeters + 1e-9)

    private fun begin(fix: Fix) {
        cuts.add(Cut(fix.position, radius()))
        quiet.add(fix.epochSec..fix.epochSec + edgeSec)
        settleUntil = fix.epochSec + edgeSec
    }

    private fun finish(fix: Fix) {
        cuts.add(Cut(fix.position, radius()))
        quiet.add(fix.epochSec - edgeSec..fix.epochSec)
    }

    /** Every fix of the ride. */
    fun onFix(position: LatLng, epochSec: Long) {
        val fix = Fix(position, epochSec)
        val previous = last
        if (previous == null) {
            begin(fix)
        } else if (epochSec - previous.epochSec >= pauseSec) {
            finish(previous)
            begin(fix)
        } else if (epochSec <= settleUntil && isJump(previous, fix)) {
            // The cold-start fix was off; the settled one marks the real start.
            cuts.add(Cut(position, radius()))
        }
        val a = anchor
        if (a == null) {
            anchor = fix
            anchorCut = false
        } else if (distanceMeters(a.position, position) > pauseMeters) {
            if (outside) {
                anchor = fix
                anchorCut = false
                outside = false
            } else {
                outside = true
            }
        } else {
            outside = false
            if (!anchorCut && epochSec - a.epochSec >= pauseSec) {
                cuts.add(Cut(a.position, radius()))
                anchorCut = true
            }
        }
        last = fix
    }

    private fun isJump(from: Fix, to: Fix): Boolean {
        val meters = distanceMeters(from.position, to.position)
        val sec = to.epochSec - from.epochSec
        return if (sec <= 0) meters > pauseMeters else meters / sec > jumpSpeedMps
    }

    /** Events stored for own learning during this ride. */
    fun add(stored: Collection<LightEvent>) {
        events.addAll(stored)
    }

    /** End of ride: the events that may be shared; the trim starts afresh. */
    fun end(): List<LightEvent> {
        val shareable = last?.let { end ->
            finish(end)
            events.filter { e ->
                cuts.none { distanceMeters(e.light, it.position) <= it.radiusMeters } && quiet.none { e.epochSec in it }
            }
        }.orEmpty()
        events.clear()
        cuts.clear()
        quiet.clear()
        last = null
        anchor = null
        anchorCut = false
        outside = false
        settleUntil = Long.MIN_VALUE
        return shareable
    }

    companion object {
        const val DEFAULT_MIN_METERS = 400.0
        const val DEFAULT_MAX_METERS = 600.0
        /** Longer than any red light (cycles are at most 150 s), so waiting at a light is no pause. */
        const val DEFAULT_PAUSE_SEC = 300L
        // Indoors GPS wanders tens of meters; a rider covers this in seconds.
        const val DEFAULT_PAUSE_METERS = 100.0
        const val DEFAULT_EDGE_SEC = 120L
        /** Faster than any bicycle (54 km/h); a jump this fast after a begin is GPS settling. */
        const val DEFAULT_JUMP_SPEED_MPS = 15.0
    }
}
