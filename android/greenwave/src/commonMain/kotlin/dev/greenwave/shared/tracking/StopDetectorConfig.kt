package dev.greenwave.shared.tracking

/**
 * Tunable thresholds for [StopDetector]. Defaults are conservative —
 * see docs/stop-events.md for the reasoning behind each one and how to
 * tune them once we have field data.
 *
 * @property speedThresholdKmh Speed below which the rider counts as
 *   standing; the first such update starts a stop. Default 1.0 km/h — well
 *   below any realistic cycling pace, well above stationary GPS noise.
 * @property resumeThresholdKmh Sliding-window average speed above which
 *   the rider counts as "definitely moving again". Hysteresis over
 *   [speedThresholdKmh]: keeps one jittery sample from ending a stop.
 * @property minStopDurationSec Shortest interval that qualifies as a
 *   stop. Slowdowns shorter than this (rolling to a stop sign, foot push)
 *   are ignored on purpose.
 * @property maxStopDurationSec Longest interval that qualifies as a stop.
 *   Stops longer than this are almost certainly pauses, not Ampeln, so
 *   they're silently dropped instead of polluting the data.
 * @property windowSize Number of recent `LocationUpdate`s (since the stop
 *   started) to average before evaluating the resume threshold. Larger =
 *   more tolerant of jitter; the event's timestamps are not delayed by it.
 */
data class StopDetectorConfig(
    val speedThresholdKmh: Double = 1.0,
    val resumeThresholdKmh: Double = 5.0,
    val minStopDurationSec: Long = 5,
    val maxStopDurationSec: Long = 300,
    val windowSize: Int = 5,
)
