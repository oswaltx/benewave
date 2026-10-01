package dev.greenwave.shared.matching

import com.bmw.hmm.SequenceState
import com.bmw.hmm.Transition
import com.bmw.hmm.ViterbiAlgorithm
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.bearingDegrees
import dev.greenwave.shared.geo.distanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Hidden-Markov map matching after Newson & Krumm (2009), "Hidden Markov map
 * matching through noise and sparseness": each fix has candidate positions on
 * nearby edges; the emission probability falls off with the distance to the fix
 * (Gaussian, [sigmaMeters]), the transition probability with how much the
 * network route between two candidates differs from the straight line between
 * the fixes (exponential, [betaMeters]). The Viterbi step is BMW Car IT's
 * hmm-lib (Apache-2.0, vendored under com.bmw.hmm).
 *
 * Fixes closer than [minStepMeters] to the previous used one are skipped, as
 * the paper recommends for dense GPS.
 */
class MapMatcher(
    private val network: RoadNetwork,
    private val sigmaMeters: Double = 6.0,
    private val betaMeters: Double = 8.0,
    private val radiusMeters: Double = 25.0,
    private val maxCandidates: Int = 6,
    private val minStepMeters: Double = 8.0,
) {
    /** The matched route: positions per used fix and the node path through the network. */
    class Match(val positions: List<RoadNetwork.Position>, val nodes: List<Int>, private val network: RoadNetwork) {
        /** The route as a polyline (matched start, network nodes, matched end). */
        val polyline: List<LatLng> by lazy {
            buildList {
                positions.firstOrNull()?.let { add(it.point) }
                nodes.forEach { add(network.nodes[it]) }
                positions.lastOrNull()?.let { add(it.point) }
            }.fold(mutableListOf<LatLng>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        }

        /** The edges the route used (in order, without repeats). */
        val edges: List<RoadNetwork.Edge> by lazy {
            val out = mutableListOf<RoadNetwork.Edge>()
            fun add(e: RoadNetwork.Edge) { if (out.lastOrNull()?.id != e.id) out.add(e) }
            positions.firstOrNull()?.let { add(it.edge) }
            for (i in 1 until nodes.size) {
                network.edgesAt(nodes[i - 1]).firstOrNull { it.from == nodes[i] || it.to == nodes[i] }?.let(::add)
            }
            positions.lastOrNull()?.let { add(it.edge) }
            out
        }

        /** Shortest distance from [p] to the matched route. */
        fun distanceTo(p: LatLng): Double =
            polyline.zipWithNext().minOfOrNull { (a, b) -> segmentDistance(p, a, b) } ?: Double.MAX_VALUE

        /**
         * How the route passes [p] (e.g. a traffic light): bearing coming in over
         * the last [spanMeters] before it and going out over the next ones, and
         * whether it stayed on the same way. Null if [p] is not near the route.
         */
        fun passage(p: LatLng, spanMeters: Double = 25.0, toleranceMeters: Double = 15.0): Passage? {
            val line = polyline
            if (line.size < 2) return null
            // Position of p along the route.
            var best = -1
            var bestT = 0.0
            var bestD = Double.MAX_VALUE
            for (i in 0 until line.size - 1) {
                val (d, t) = segmentDistanceAndT(p, line[i], line[i + 1])
                if (d < bestD) { bestD = d; best = i; bestT = t }
            }
            if (bestD > toleranceMeters) return null
            val cum = DoubleArray(line.size)
            for (i in 1 until line.size) cum[i] = cum[i - 1] + distanceMeters(line[i - 1], line[i])
            val at = cum[best] + bestT * (cum[best + 1] - cum[best])
            fun pointAt(s: Double): LatLng {
                val x = s.coerceIn(0.0, cum.last())
                var i = cum.indexOfLast { it <= x }.coerceAtMost(line.size - 2)
                if (i < 0) i = 0
                val seg = cum[i + 1] - cum[i]
                val f = if (seg == 0.0) 0.0 else (x - cum[i]) / seg
                val a = line[i]
                val b = line[i + 1]
                return LatLng(a.latitude + f * (b.latitude - a.latitude), a.longitude + f * (b.longitude - a.longitude))
            }
            val here = pointAt(at)
            val before = pointAt(at - spanMeters)
            val after = pointAt(at + spanMeters)
            val inBearing = if (distanceMeters(before, here) < 3.0) null else bearingDegrees(before, here)
            val outBearing = if (distanceMeters(here, after) < 3.0) null else bearingDegrees(here, after)
            val wayBefore = edgeNear(before)?.way?.id
            val wayAfter = edgeNear(after)?.way?.id
            return Passage(inBearing, outBearing, sameWay = wayBefore != null && wayBefore == wayAfter)
        }

        private fun edgeNear(p: LatLng): RoadNetwork.Edge? = edges.minByOrNull { network.project(p, it).distanceMeters }
    }

    /** See [Match.passage]. */
    data class Passage(val inBearing: Double?, val outBearing: Double?, val sameWay: Boolean)

    fun match(fixes: List<LatLng>): Match? {
        val used = mutableListOf<LatLng>()
        for (f in fixes) if (used.isEmpty() || distanceMeters(used.last(), f) >= minStepMeters) used.add(f)
        if (used.size < 2) return null

        val viterbi = ViterbiAlgorithm<RoadNetwork.Position, LatLng, List<Int>>()
        var prevFix: LatLng? = null
        var prevCandidates: List<RoadNetwork.Position> = emptyList()
        for (fix in used) {
            val candidates = network.candidates(fix, radiusMeters, maxCandidates)
            if (candidates.isEmpty()) continue
            val emissions = candidates.associateWith { emissionLog(it.distanceMeters) }
            if (!viterbi.processingStarted()) {
                viterbi.startWithInitialObservation(fix, candidates, emissions)
            } else {
                val straight = distanceMeters(prevFix!!, fix)
                val bound = straight * 3 + 100.0
                val transitions = HashMap<Transition<RoadNetwork.Position>, Double>()
                val descriptors = HashMap<Transition<RoadNetwork.Position>, List<Int>>()
                for (from in prevCandidates) {
                    val reach = network.reach(from, bound)
                    for (to in candidates) {
                        val (routeMeters, path) = network.route(reach, to) ?: continue
                        val t = Transition(from, to)
                        transitions[t] = transitionLog(abs(routeMeters - straight))
                        descriptors[t] = path
                    }
                }
                viterbi.nextStep(fix, candidates, emissions, transitions, descriptors)
                if (viterbi.isBroken) break
            }
            prevFix = fix
            prevCandidates = candidates
        }
        if (!viterbi.processingStarted()) return null
        val sequence: List<SequenceState<RoadNetwork.Position, LatLng, List<Int>>> = viterbi.computeMostLikelySequence()
        if (sequence.isEmpty()) return null
        val nodes = mutableListOf<Int>()
        for (s in sequence) s.transitionDescriptor?.forEach { if (nodes.lastOrNull() != it) nodes.add(it) }
        return Match(sequence.map { it.state }, nodes, network)
    }

    private fun emissionLog(d: Double) = -0.5 * (d / sigmaMeters) * (d / sigmaMeters) - ln(sqrt(2 * PI) * sigmaMeters)

    private fun transitionLog(diff: Double) = -diff / betaMeters - ln(betaMeters)

    private companion object {
        fun segmentDistance(p: LatLng, a: LatLng, b: LatLng) = segmentDistanceAndT(p, a, b).first

        /** Distance from [p] to segment a–b and the position on it (0..1), flat approximation. */
        fun segmentDistanceAndT(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
            val k = kotlin.math.cos(Math.toRadians(p.latitude)) * 111_320.0
            val ax = (a.longitude - p.longitude) * k
            val ay = (a.latitude - p.latitude) * 111_320.0
            val bx = (b.longitude - p.longitude) * k
            val by = (b.latitude - p.latitude) * 111_320.0
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            return sqrt(x * x + y * y) to t
        }
    }
}
