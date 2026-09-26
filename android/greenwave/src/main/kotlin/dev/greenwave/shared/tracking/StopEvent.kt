package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng

/**
 * One observed stop: a contiguous interval where the rider's speed stayed
 * below [dev.greenwave.shared.tracking.StopDetectorConfig.speedThresholdKmh]
 * for at least
 * [dev.greenwave.shared.tracking.StopDetectorConfig.minStopDurationSec],
 * then resumed above
 * [dev.greenwave.shared.tracking.StopDetectorConfig.resumeThresholdKmh].
 *
 * The fields are deliberately the minimum Phase 3 needs to match the stop
 * against an OSM `highway=traffic_signals` node, and Phase 4 needs to
 * upload an anonymous aggregate. Anything richer (acceleration profile,
 * idle jitter, surrounding speed) can be added later without breaking the
 * event shape.
 *
 * @property startEpochSec Unix-second timestamp of the first update whose
 *   speed dropped below the stop threshold (the rider arrived).
 * @property endEpochSec Unix-second timestamp of the first update of the
 *   take-off, i.e. the rolling-off that brought the sliding-window speed
 *   above the resume threshold (at an Ampel: roughly when it turned green).
 * @property durationSec `endEpochSec - startEpochSec`. Always ≥
 *   `minStopDurationSec` and ≤ `maxStopDurationSec` (the detector drops
 *   anything outside that range).
 * @property position Last known position while the rider was stopped.
 *   This is what Phase 3 will snap to the nearest Ampel node.
 * @property minSpeedKmh Lowest `speedKmh` observed during the stop window.
 *   Mostly zero; non-zero values usually mean GPS jitter at a red light.
 * @property maxSpeedKmh Peak `speedKmh` observed in the short resume
 *   window just after the stop ended. Useful to distinguish "pushed off"
 *   (~0) from "cycled off" (~10+).
 */

data class StopEvent(
    val startEpochSec: Long,
    val endEpochSec: Long,
    val durationSec: Long,
    val position: LatLng,
    val minSpeedKmh: Double,
    val maxSpeedKmh: Double,
)
