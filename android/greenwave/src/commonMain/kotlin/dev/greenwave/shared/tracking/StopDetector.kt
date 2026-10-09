package dev.greenwave.shared.tracking

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate

/**
 * Detects stop-and-go events from a stream of [LocationUpdate]s.
 *
 * Feed every [LocationUpdate] the rider's [LocationSource] produces into
 * [onUpdate] in time order. Each call returns a [StopEvent] exactly once,
 * the moment a stop ends — and `null` for everything else. That makes the
 * detector easy to drop into `GreenWaveViewModel`'s existing `combine`
 * block: just call `onUpdate(location)` for every emitted update and
 * forward non-null results to wherever Phase 4 will persist them.
 *
 * The detector is stateful but single-rider: one instance per active ride.
 * It is **not** thread-safe — call [onUpdate] from a single coroutine
 * (which is what the existing `combine` block already does).
 *
 * State machine: see docs/stop-events.md.
 */
class StopDetector(
    private val config: StopDetectorConfig = StopDetectorConfig(),
) {

    private enum class State { Moving, Stopped }

    private var state: State = State.Moving

    // Sliding window of speed readings (km/h) taken since the current stop
    // began. Cleared on every stop start so the cruising speed before the
    // stop can't delay or mask it.
    private val recentSpeeds: ArrayDeque<Double> = ArrayDeque()

    // Stop bookkeeping, captured the instant the stop starts.
    private var stopStartEpochSec: Long = 0L
    private var minSpeedDuringStop: Double = Double.POSITIVE_INFINITY
    private var lastPositionWhileStopped: LatLng = LatLng(0.0, 0.0)

    // Start of the current run of samples at or above speedThresholdKmh
    // (null while standing) and the peak speed within that run. When the
    // stop ends, this run is the rider's take-off: its first sample is the
    // moment they actually started rolling again.
    private var takeOffEpochSec: Long? = null
    private var maxSpeedInTakeOff: Double = 0.0

    /**
     * Consume one [LocationUpdate]. Returns a [StopEvent] exactly when a
     * stop ends (i.e. the window average crosses
     * [StopDetectorConfig.resumeThresholdKmh]) and its duration lies within
     * [StopDetectorConfig.minStopDurationSec]..[StopDetectorConfig.maxStopDurationSec],
     * and `null` for every other update.
     */
    fun onUpdate(update: LocationUpdate): StopEvent? =
        when (state) {
            State.Moving -> stepFromMoving(update)
            State.Stopped -> stepFromStopped(update)
        }

    private fun stepFromMoving(update: LocationUpdate): StopEvent? {
        if (update.speedKmh < config.speedThresholdKmh) {
            state = State.Stopped
            stopStartEpochSec = update.epochSec
            minSpeedDuringStop = update.speedKmh
            lastPositionWhileStopped = update.position
            takeOffEpochSec = null
            maxSpeedInTakeOff = 0.0
            recentSpeeds.clear()
            recentSpeeds.addLast(update.speedKmh)
        }
        return null
    }

    private fun stepFromStopped(update: LocationUpdate): StopEvent? {
        recentSpeeds.addLast(update.speedKmh)
        while (recentSpeeds.size > config.windowSize) recentSpeeds.removeFirst()

        if (update.speedKmh < minSpeedDuringStop) minSpeedDuringStop = update.speedKmh

        if (update.speedKmh < config.speedThresholdKmh) {
            lastPositionWhileStopped = update.position
            takeOffEpochSec = null
            maxSpeedInTakeOff = 0.0
            return null
        }

        val endEpochSec = takeOffEpochSec ?: update.epochSec
        takeOffEpochSec = endEpochSec
        if (update.speedKmh > maxSpeedInTakeOff) maxSpeedInTakeOff = update.speedKmh

        // A single jittery sample doesn't end the stop: only once the window
        // average clearly exceeds the resume threshold is the rider moving.
        if (recentSpeeds.sum() / recentSpeeds.size <= config.resumeThresholdKmh) return null

        state = State.Moving
        val duration = endEpochSec - stopStartEpochSec
        // Too short = slow roll, too long = pause rather than an Ampel.
        if (duration < config.minStopDurationSec || duration > config.maxStopDurationSec) return null

        return StopEvent(
            startEpochSec = stopStartEpochSec,
            endEpochSec = endEpochSec,
            durationSec = duration,
            position = lastPositionWhileStopped,
            minSpeedKmh = minSpeedDuringStop,
            maxSpeedKmh = maxSpeedInTakeOff,
        )
    }
}
