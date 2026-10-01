package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.angleDifference
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
    /** Direction the rider left the junction in, null if unknown (older logs, ride ended at the light). */
    val exitHeadingDeg: Int? = null,
    /**
     * From map matching: true if the rider was on a separate way (cycleway,
     * footway) alongside the way the signal is on, false if on that way itself,
     * null if unknown. Whether a carriageway signal governs the parallel
     * cycleway depends on the junction (in Germany it does unless there is a
     * bicycle signal), so such events are kept and marked rather than dropped.
     */
    val sideway: Boolean? = null,
) {
    /** What the rider did at the junction; different turns often have their own green phase. */
    enum class Turn { STRAIGHT, LEFT, RIGHT, U_TURN }

    /** The turn from [headingDeg] to [exitHeadingDeg], null while either is unknown. */
    val turn: Turn?
        get() = if (headingDeg == null || exitHeadingDeg == null) null
        else turnOf(headingDeg.toDouble(), exitHeadingDeg.toDouble())

    /**
     * Same approach (see [sameApproach]) and, where both sides know it, the same
     * [turn]. Events without a turn match any, so older logs keep counting.
     */
    fun sameMovement(approachDeg: Double?, turn: Turn?, toleranceDeg: Double = 45.0): Boolean =
        sameApproach(approachDeg, toleranceDeg) && (turn == null || this.turn == null || this.turn == turn)

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
        /** Straight within ±35°, a U-turn beyond ±150°, else left or right. */
        fun turnOf(approachDeg: Double, exitDeg: Double): Turn {
            val d = angleDifference(approachDeg, exitDeg)
            return when {
                abs(d) <= 35.0 -> Turn.STRAIGHT
                abs(d) > 150.0 -> Turn.U_TURN
                d > 0 -> Turn.RIGHT
                else -> Turn.LEFT
            }
        }

        /** A detected stop at [light] as its two events. */
        fun fromStop(stop: StopEvent, light: LatLng, headingDeg: Int?): List<LightEvent> = listOf(
            LightEvent(light, stop.startEpochSec, Kind.STOP_START, headingDeg),
            LightEvent(light, stop.endEpochSec, Kind.STOP_END, headingDeg),
        )
    }
}
