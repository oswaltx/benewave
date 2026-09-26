package dev.greenwave.shared.guidance

import kotlin.math.abs

/**
 * What the rider should do with their pace right now, relative to the
 * optimizer's recommended speed. Drives the colour + arrow of the floating
 * overlay (and anything else that wants a glanceable "schneller / halten /
 * langsamer" instead of two numbers to compare).
 */
enum class SpeedAdvice {
    /** Below the recommendation by more than the tolerance. */
    FASTER,

    /** Within the tolerance band around the recommendation. */
    HOLD,

    /** Above the recommendation by more than the tolerance. */
    SLOWER,

    /** No recommendation available (no lights ahead / no GPS fix yet). */
    NONE,
}

object SpeedAdvisor {

    /**
     * Same ±2 km/h band the Tacho screen uses for its "on target" green —
     * tighter than that and GPS speed jitter makes the advice flicker at a
     * constant pace; looser and it stops being actionable.
     */
    const val DEFAULT_TOLERANCE_KMH: Double = 2.0

    fun advise(
        currentSpeedKmh: Double,
        targetSpeedKmh: Double?,
        toleranceKmh: Double = DEFAULT_TOLERANCE_KMH,
    ): SpeedAdvice {
        if (targetSpeedKmh == null) return SpeedAdvice.NONE
        val diff = currentSpeedKmh - targetSpeedKmh
        return when {
            abs(diff) <= toleranceKmh -> SpeedAdvice.HOLD
            diff < 0 -> SpeedAdvice.FASTER
            else -> SpeedAdvice.SLOWER
        }
    }
}
