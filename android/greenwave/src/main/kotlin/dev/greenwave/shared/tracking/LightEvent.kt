package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import kotlin.math.abs

/**
 * One observation at one traffic light — the unit greenWave learns from and,
 * later, what riders share (no track, no rider id; see CROWDSOURCING-KONZEPT).
 */
data class LightEvent(
    /** The light's position as known on the device (OSM node). */
    val light: LatLng,
    val epochSec: Long,
    val kind: Kind,
    /** Direction of approach in degrees (0 = north), null if unknown (old stop logs). */
    val headingDeg: Int?,
) {
    enum class Kind {
        /** Stopped here: red from this moment on. */
        STOP_START,

        /** Rode off after a stop: green starts about now. */
        STOP_END,

        /** Rode through without stopping: green at this moment. */
        PASS_GREEN,
    }

    /** Same approach as [headingDeg] (within [toleranceDeg]); unknown headings match any. */
    fun sameApproach(otherHeadingDeg: Double?, toleranceDeg: Double = 45.0): Boolean {
        if (headingDeg == null || otherHeadingDeg == null) return true
        val diff = abs(((headingDeg - otherHeadingDeg) % 360 + 540) % 360 - 180)
        return diff <= toleranceDeg
    }

    companion object {
        /** A detected stop at [light] as its two events. */
        fun fromStop(stop: StopEvent, light: LatLng, headingDeg: Int?): List<LightEvent> = listOf(
            LightEvent(light, stop.startEpochSec, Kind.STOP_START, headingDeg),
            LightEvent(light, stop.endEpochSec, Kind.STOP_END, headingDeg),
        )
    }
}
