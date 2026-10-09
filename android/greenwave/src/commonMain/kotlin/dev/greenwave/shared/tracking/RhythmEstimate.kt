package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng

/**
 * The result of statically estimating one traffic light's signal program
 * from observed [StopEvent]s — see
 * [TrafficLightRhythmEstimator.estimate]. Mirrors the shape of
 * [dev.greenwave.shared.model.TrafficLight] so that callers can
 * construct a fully-fledged [dev.greenwave.shared.model.TrafficLight] by
 * combining the estimate with a known position.
 *
 * @property cycleLengthSec length of one full cycle, in seconds.
 * @property greenStartOffsetSec seconds (mod [cycleLengthSec]) at which
 *   the green phase starts, measured relative to the Unix epoch — same
 *   convention as [dev.greenwave.shared.model.TrafficLight] so the
 *   greenWave optimizer's existing cycle math works without change.
 * @property greenDurationSec how long the green phase lasts, in seconds.
 * @property sampleCount number of [StopEvent]s that fed the estimate.
 * @property confidence heuristic in `[0.0, 1.0]` that callers can use to
 *   decide whether to trust this estimate enough to swap out the
 *   random fallback from `RandomTrafficTimingGenerator`.
 */

data class RhythmEstimate(
    val cycleLengthSec: Int,
    val greenStartOffsetSec: Int,
    val greenDurationSec: Int,
    val sampleCount: Int,
    val confidence: Double,
)

/**
 * A traffic-light position plus the rhythm estimate we have for it (if
 * any). Wraps [RhythmEstimate] with the [LatLng] so the UI / repository
 * code can iterate the map without needing a parallel position list.
 */

data class MeasuredRhythm(
    val position: LatLng,
    val estimate: RhythmEstimate,
)