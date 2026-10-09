package dev.greenwave.shared.matching.hmm

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Umbrella example from hmm-lib's ViterbiAlgorithmTest, plus HMM break and descriptors. */
class ViterbiAlgorithmTest {
    private val rain = "rain"
    private val sun = "sun"
    private val states = listOf(rain, sun)

    private fun emissions(umbrella: Boolean) =
        if (umbrella) mapOf(rain to ln(0.9), sun to ln(0.2)) else mapOf(rain to ln(0.1), sun to ln(0.8))

    private val transitions = mapOf(
        Transition(rain, rain) to ln(0.7), Transition(rain, sun) to ln(0.3),
        Transition(sun, rain) to ln(0.3), Transition(sun, sun) to ln(0.7),
    )

    @Test
    fun umbrellaSequence() {
        val observations = listOf(true, true, false, true, true)
        val viterbi = ViterbiAlgorithm<String, Boolean, String>()
        viterbi.startWithInitialObservation(observations[0], states, emissions(observations[0]))
        for (o in observations.drop(1)) {
            val descriptors = transitions.keys.associateWith { "${it.fromCandidate}>${it.toCandidate}" }
            viterbi.nextStep(o, states, emissions(o), transitions, descriptors)
        }
        val sequence = viterbi.computeMostLikelySequence()
        assertEquals(listOf(rain, rain, sun, rain, rain), sequence.map { it.state })
        assertEquals(observations, sequence.map { it.observation })
        assertNull(sequence[0].transitionDescriptor)
        assertEquals(listOf("rain>rain", "rain>sun", "sun>rain", "rain>rain"), sequence.drop(1).map { it.transitionDescriptor })
    }

    @Test
    fun breakWhenNoTransitionPossible() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        viterbi.startWithInitialObservation(0, listOf(rain), mapOf(rain to 0.0))
        viterbi.nextStep(1, listOf(sun), mapOf(sun to 0.0), emptyMap())
        assertTrue(viterbi.isBroken)
        // The sequence up to the break stays available.
        assertEquals(listOf(rain), viterbi.computeMostLikelySequence().map { it.state })
    }

    @Test
    fun emptyBeforeStart() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        assertFalse(viterbi.processingStarted())
        assertTrue(viterbi.computeMostLikelySequence().isEmpty())
    }

    @Test
    fun tieGoesToTheFirstCandidate() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        viterbi.startWithInitialObservation(0, listOf(sun, rain), mapOf(sun to ln(0.5), rain to ln(0.5)))
        assertEquals(listOf(sun), viterbi.computeMostLikelySequence().map { it.state })
    }

    @Test
    fun impossibleCandidateIsNeverChosen() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        viterbi.startWithInitialObservation(0, states, mapOf(rain to ln(0.5), sun to ln(0.5)))
        // sun cannot be emitted at step 1 although its transitions are likely.
        viterbi.nextStep(1, states, mapOf(rain to ln(0.1), sun to Double.NEGATIVE_INFINITY), transitions)
        assertFalse(viterbi.isBroken)
        assertEquals(listOf(rain, rain), viterbi.computeMostLikelySequence().map { it.state })
    }

    @Test
    fun breakAtStartLeavesNothing() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        viterbi.startWithInitialObservation(0, states, mapOf(rain to Double.NEGATIVE_INFINITY, sun to Double.NEGATIVE_INFINITY))
        assertTrue(viterbi.isBroken)
        assertFalse(viterbi.processingStarted())
        assertTrue(viterbi.computeMostLikelySequence().isEmpty())
    }

    @Test
    fun noStepAfterBreak() {
        val viterbi = ViterbiAlgorithm<String, Int, Unit>()
        viterbi.startWithInitialObservation(0, listOf(rain), mapOf(rain to 0.0))
        viterbi.nextStep(1, listOf(sun), mapOf(sun to 0.0), emptyMap())
        assertFailsWith<IllegalStateException> { viterbi.nextStep(2, listOf(sun), mapOf(sun to 0.0), emptyMap()) }
    }
}
