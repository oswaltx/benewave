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

    /** Development: after every ride, write the full export to Documents/greenWave (see DataExport). */
    var autoExport: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_EXPORT, value).apply()

    /**
     * Share anonymised light events with other riders (weekly, see [ShareWorker])
     * and learn from theirs. Off by default: the rider opts in.
     */
    var shareEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHARE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARE, value).apply()

    /**
     * Base address of the shared board, e.g. https://host:port; empty = not set,
     * nothing is sent. No board is preset
     * ([DEFAULT_SHARE_URL] is empty); sharing itself stays off until switched on.
     */
    var shareServerUrl: String
        get() = prefs.getString(KEY_SHARE_URL, DEFAULT_SHARE_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_SHARE_URL, value.trim()).apply()

    /** Planned moment of the next send (epoch seconds), 0 = not planned yet. */
    var shareNextAt: Long
        get() = prefs.getLong(KEY_SHARE_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_SHARE_NEXT, value).apply()

    /** Last successful exchange (epoch seconds), 0 = never. */
    var shareLastSent: Long
        get() = prefs.getLong(KEY_SHARE_LAST, 0L)
        set(value) = prefs.edit().putLong(KEY_SHARE_LAST, value).apply()

    /** Planned moment of the next download (epoch seconds), 0 = not planned yet; kept apart from the send. */
    var shareFetchAt: Long
        get() = prefs.getLong(KEY_SHARE_FETCH_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SHARE_FETCH_AT, value).apply()

    /**
     * The board's signed device token ("id.sig", random, no link to the rider),
     * empty = not registered. Forgotten when sharing is switched off, so a
     * rider who turns it on again starts with a new one.
     */
    var shareDevice: String
        get() = prefs.getString(KEY_SHARE_DEVICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SHARE_DEVICE, value).apply()

    /** Last successful download of shared events (epoch seconds), 0 = never. */
    var shareLastFetch: Long
        get() = prefs.getLong(KEY_SHARE_FETCH, 0L)
        set(value) = prefs.edit().putLong(KEY_SHARE_FETCH, value).apply()

    private companion object {
        /** No board preset: riders enter the address of a board they trust. */
        const val DEFAULT_SHARE_URL = ""
        const val KEY_SHARE = "share_enabled"
        const val KEY_SHARE_URL = "share_server_url"
        const val KEY_SHARE_NEXT = "share_next_at"
        const val KEY_SHARE_LAST = "share_last_sent"
        const val KEY_SHARE_FETCH = "share_last_fetch"
        const val KEY_SHARE_FETCH_AT = "share_fetch_at"
        const val KEY_SHARE_DEVICE = "share_device"
        const val KEY_AUTO_EXPORT = "auto_export"
        const val KEY_MIN = "range_min_kmh"
        const val KEY_MAX = "range_max_kmh"
        const val KEY_AUTO = "auto_profile"
        const val KEY_RUNS_RED = "runs_red"
        const val KEY_LOGGING = "logging_enabled"
        const val DEFAULT_MIN = 10f
        const val DEFAULT_MAX = 25f
    }
}
