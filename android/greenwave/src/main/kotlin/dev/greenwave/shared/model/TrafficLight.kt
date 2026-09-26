package dev.greenwave.shared.model

import dev.greenwave.shared.geo.LatLng

/**
 * @param cycleLengthSec length of one full signal cycle, in seconds.
 * @param greenStartOffsetSec second (relative to the Unix epoch, 1970-01-01
 *   00:00 UTC, modulo [cycleLengthSec]) at which the green phase starts.
 *   Storing it relative to the epoch — instead of e.g. "device start time" —
 *   means the cycle position for any given light is computable independent
 *   of when the app was launched.
 * @param greenDurationSec how long the green phase lasts, in seconds.
 */

data class TrafficLight(
    val position: LatLng,
    val cycleLengthSec: Int,
    val greenStartOffsetSec: Int,
    val greenDurationSec: Int,
)
