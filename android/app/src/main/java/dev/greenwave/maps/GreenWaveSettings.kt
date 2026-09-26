package dev.greenwave.maps

import android.content.Context
import dev.greenwave.shared.algorithm.SpeedRangeKmh

/** greenWave's own preferences, kept apart from CoMaps' settings. */
internal class GreenWaveSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("greenwave", Context.MODE_PRIVATE)

    var manualRange: SpeedRangeKmh
        get() = SpeedRangeKmh(
            min = prefs.getFloat(KEY_MIN, DEFAULT_MIN).toDouble(),
            max = prefs.getFloat(KEY_MAX, DEFAULT_MAX).toDouble(),
        )
        set(value) = prefs.edit()
            .putFloat(KEY_MIN, value.min.toFloat())
            .putFloat(KEY_MAX, value.max.toFloat())
            .apply()

    /** Derive the speed range from past rides instead of [manualRange]. */
    var autoProfile: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** Log stops and rides while navigating by bicycle (the data the cycles are learned from). */
    var loggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGING, true)
        set(value) = prefs.edit().putBoolean(KEY_LOGGING, value).apply()

    /**
     * Rider says they regularly cross on red. Their rides are flagged so they
     * never feed shared timing data (crowdsourcing) — a red-runner's
     * pass-throughs would look like green phases.
     */
    var runsRed: Boolean
        get() = prefs.getBoolean(KEY_RUNS_RED, false)
        set(value) = prefs.edit().putBoolean(KEY_RUNS_RED, value).apply()

    private companion object {
        const val KEY_MIN = "range_min_kmh"
        const val KEY_MAX = "range_max_kmh"
        const val KEY_AUTO = "auto_profile"
        const val KEY_RUNS_RED = "runs_red"
        const val KEY_LOGGING = "logging_enabled"
        const val DEFAULT_MIN = 10f
        const val DEFAULT_MAX = 25f
    }
}
