package dev.greenwave.shared.tracking

import kotlin.math.max
import kotlin.math.pow

/**
 * Statically estimates one traffic light's signal program from a bag of
 * observed [StopEvent]s attributed to it. Pure: no I/O, no clock reads,
 * no coroutines — call it whenever the bag changes.
 *
 * The idea: every stop the rider makes at this light happened during the
 * red phase and ended when it turned green. Their `endEpochSec` modulo the true cycle length `C` therefore lands on the
 * same phase every time — the green start — while for a wrong `C` it
 * scatters. Picking the cycle that clusters those departure phases the
 * tightest picks the cycle; the cluster's position is where green starts.
 * The arrival phases (`startEpochSec mod C`) spread over the whole red
 * window, so their cluster width tells us how long red lasts.
 *
 * Departures are used for choosing `C` because they are a sharp event:
 * arrivals cover the whole red window, so for long red phases they are
 * hard to tell apart from randomly timed stops.
 *
 * Limitations:
 * - Only red-phase stops feed in. A rider who always catches green
 *   teaches us nothing about the cycle, only that it ran.
 * - Queue-extended stops (waiting behind several bikes) push duration
 *   upward, so [RhythmEstimate.greenDurationSec] should be treated as
 *   an upper bound, not an exact figure.
 * - Few samples (< [MIN_SAMPLES]) produce noisy estimates; the function
 *   returns `null` below the threshold rather than a low-confidence
 *   guess.
 */
object TrafficLightRhythmEstimator {

    /** Default candidate cycle lengths — typical German signal cycles. */
    val DEFAULT_CANDIDATE_CYCLES: List<Int> = listOf(45, 60, 75, 90, 105, 120)

    /** Below this many samples the function refuses to estimate. */
    const val MIN_SAMPLES: Int = 3

    /**
     * @return an estimate, or `null` if there are too few samples or even
     *   the best candidate's departures are not clustered tighter than
     *   randomly timed stops plausibly would be (i.e. data is too noisy
     *   to trust).
     */
    fun estimate(
        events: List<StopEvent>,
        candidateCycles: List<Int> = DEFAULT_CANDIDATE_CYCLES,
    ): RhythmEstimate? {
        if (events.size < MIN_SAMPLES) return null

        // Rank by the fraction of the cycle the cluster covers, not its
        // absolute width: random phases on a shorter ring are always
        // narrower in seconds, which would bias towards the shortest
        // candidate (and towards C/2 when the true cycle is C).
        val rankedCycles = candidateCycles
            .map { cycle -> cycle to clusterRange(events.map { it.endEpochSec }, cycle) }
            .sortedWith(
                compareBy<Pair<Int, ClusterInfo>>({ it.second.width / it.first })
                    .thenBy { it.first },
            )

        val (bestCycle, departures) = rankedCycles.firstOrNull() ?: return null
        // Chance that randomly timed stops would cluster at least this
        // tightly for some candidate. Split the budget over all candidates,
        // since we picked the best of them.
        val noiseProbability = randomGapProbability(1.0 - departures.width / bestCycle, events.size)
        if (noiseProbability >= MAX_NOISE_PROBABILITY / candidateCycles.size) return null

        val arrivals = clusterRange(events.map { it.startEpochSec }, bestCycle)
        val medianDuration = median(events.map { it.durationSec.toDouble() })
        val redDuration = max(
            arrivals.width,
            2.0 * medianDuration,
        ).coerceIn(MIN_RED_DURATION.toDouble(), (bestCycle - MIN_GREEN_DURATION).toDouble())
            .toInt()

        val greenDuration = bestCycle - redDuration

        // Median measured from the cluster's start so a cluster wrapping
        // around 0 doesn't average to the opposite side of the ring.
        val medianOffsetInCluster = median(
            events.map { phase(it.endEpochSec - departures.start.toLong(), bestCycle).toDouble() },
        )
        val greenStartOffset = phase(departures.start.toLong() + medianOffsetInCluster.toLong(), bestCycle).toInt()

        val tightnessFactor = (1.0 - departures.width / bestCycle).coerceIn(0.0, 1.0)
        val sampleFactor = (events.size.toDouble() / CONFIDENCE_SAMPLE_TARGET).coerceAtMost(1.0)
        val confidence = (tightnessFactor * sampleFactor).coerceIn(0.0, 1.0)

        return RhythmEstimate(
            cycleLengthSec = bestCycle,
            greenStartOffsetSec = greenStartOffset,
            greenDurationSec = greenDuration,
            sampleCount = events.size,
            confidence = confidence,
        )
    }

    private fun phase(epochSec: Long, cycle: Int): Long = ((epochSec % cycle) + cycle) % cycle

    /**
     * The tightest interval (on the ring `[0, cycle)`) that contains
     * every one of [epochSecs] mod cycle. Returns the width of that
     * interval plus its endpoints.
     */
    private fun clusterRange(epochSecs: List<Long>, cycle: Int): ClusterInfo {
        val phases = epochSecs.map { phase(it, cycle) }.sorted()
        val n = phases.size

        // Largest gap between consecutive phases (with the wrap-around
        // gap from last back to first folded into the list). The smallest
        // interval that covers them all is the complement of that gap.
        var maxGap = (phases[0] + cycle - phases[n - 1]).toDouble()
        var maxGapIdx = n - 1
        for (i in 0 until n - 1) {
            val gap = (phases[i + 1] - phases[i]).toDouble()
            if (gap > maxGap) {
                maxGap = gap
                maxGapIdx = i
            }
        }

        val end = phases[maxGapIdx].toDouble()
        val start = phases[(maxGapIdx + 1) % n].toDouble()
        val width = cycle - maxGap
        return ClusterInfo(start = start, end = end, width = width)
    }

    private data class ClusterInfo(val start: Double, val end: Double, val width: Double)

    /**
     * Upper bound on the probability that [n] points placed uniformly at
     * random on a ring leave a gap of at least [gapFraction] of the ring
     * (union bound over the n gaps: `n · (1 − g)^(n−1)`).
     */
    private fun randomGapProbability(gapFraction: Double, n: Int): Double =
        (n * (1.0 - gapFraction).coerceAtLeast(0.0).pow(n - 1)).coerceAtMost(1.0)

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }

    // Red has to be at least 3 s (anything shorter is GPS jitter / a
    // pedestrian phase we shouldn't be optimising around), green at
    // least 3 s too (a 1-second green would be a flashing beacon).
    private const val MIN_RED_DURATION: Int = 3
    private const val MIN_GREEN_DURATION: Int = 3

    // Accept an estimate only if randomly timed stops would produce an
    // equally tight departure cluster with less than this probability.
    private const val MAX_NOISE_PROBABILITY: Double = 0.05

    // How many samples give us full confidence in the sample-count term.
    private const val CONFIDENCE_SAMPLE_TARGET: Double = 10.0
}
