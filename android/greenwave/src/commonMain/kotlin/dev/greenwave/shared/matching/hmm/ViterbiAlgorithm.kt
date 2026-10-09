/*
 * Copyright (C) 2015-2016, BMW Car IT GmbH and BMW AG
 * Author: Stefan Holder (stefan.holder@bmw.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified 2026 for greenWave: Kotlin port of com.bmw.hmm (hmm-lib) so it builds for
 * Kotlin Multiplatform. Reduced to what MapMatcher uses: no smoothing probabilities
 * (forward-backward) and no message history. The algorithm is otherwise unchanged.
 */

package dev.greenwave.shared.matching.hmm

/** Transition between two consecutive candidates. */
data class Transition<S>(val fromCandidate: S, val toCandidate: S)

/** State of the most likely sequence with its observation and transition descriptor. */
class SequenceState<S, O, D>(val state: S, val observation: O?, val transitionDescriptor: D?)

/**
 * Viterbi algorithm for time-inhomogeneous Markov processes with log probabilities.
 * Call [startWithInitialObservation] once, then [nextStep] per time step, and finally
 * [computeMostLikelySequence]. After an HMM break ([isBroken]) no further steps are allowed.
 */
class ViterbiAlgorithm<S, O, D> {
    private class ExtendedState<S, O, D>(
        val state: S,
        val backPointer: ExtendedState<S, O, D>?,
        val observation: O?,
        val transitionDescriptor: D?,
    )

    private class ForwardStepResult<S, O, D> {
        val newMessage = LinkedHashMap<S, Double>()
        val newExtendedStates = LinkedHashMap<S, ExtendedState<S, O, D>>()
    }

    private var lastExtendedStates: MutableMap<S, ExtendedState<S, O, D>> = LinkedHashMap()
    private var prevCandidates: List<S> = emptyList()
    private var message: Map<S, Double>? = null

    /** True if the last step made every candidate impossible. */
    var isBroken = false
        private set

    fun processingStarted(): Boolean = message != null

    fun startWithInitialObservation(observation: O, candidates: Collection<S>, emissionLogProbabilities: Map<S, Double>) {
        check(!processingStarted()) { "Initial probabilities have already been set." }
        val initialMessage = LinkedHashMap<S, Double>()
        for (candidate in candidates) {
            initialMessage[candidate] = emissionLogProbabilities[candidate]
                ?: throw NullPointerException("No initial probability for $candidate")
        }
        isBroken = hmmBreak(initialMessage)
        if (isBroken) return
        message = initialMessage
        lastExtendedStates = LinkedHashMap()
        for (candidate in candidates) lastExtendedStates[candidate] = ExtendedState(candidate, null, observation, null)
        prevCandidates = candidates.toList()
    }

    fun nextStep(
        observation: O,
        candidates: Collection<S>,
        emissionLogProbabilities: Map<S, Double>,
        transitionLogProbabilities: Map<Transition<S>, Double>,
        transitionDescriptors: Map<Transition<S>, D> = emptyMap(),
    ) {
        val current = checkNotNull(message) { "startWithInitialObservation() must be called first." }
        check(!isBroken) { "Method must not be called after an HMM break." }
        val result = forwardStep(observation, prevCandidates, candidates, current, emissionLogProbabilities, transitionLogProbabilities, transitionDescriptors)
        isBroken = hmmBreak(result.newMessage)
        if (isBroken) return
        message = result.newMessage
        lastExtendedStates = result.newExtendedStates
        prevCandidates = candidates.toList()
    }

    /** Most likely sequence of states, oldest first; empty if processing never started. */
    fun computeMostLikelySequence(): List<SequenceState<S, O, D>> {
        if (message == null) return emptyList()
        val result = ArrayList<SequenceState<S, O, D>>()
        var es = lastExtendedStates[mostLikelyState()]
        while (es != null) {
            result.add(SequenceState(es.state, es.observation, es.transitionDescriptor))
            es = es.backPointer
        }
        result.reverse()
        return result
    }

    private fun hmmBreak(message: Map<S, Double>): Boolean = message.values.all { it == Double.NEGATIVE_INFINITY }

    private fun forwardStep(
        observation: O,
        prevCandidates: List<S>,
        curCandidates: Collection<S>,
        message: Map<S, Double>,
        emissionLogProbabilities: Map<S, Double>,
        transitionLogProbabilities: Map<Transition<S>, Double>,
        transitionDescriptors: Map<Transition<S>, D>,
    ): ForwardStepResult<S, O, D> {
        val result = ForwardStepResult<S, O, D>()
        for (curState in curCandidates) {
            var maxLogProbability = Double.NEGATIVE_INFINITY
            var maxPrevState: S? = null
            var found = false
            for (prevState in prevCandidates) {
                val logProbability = message.getValue(prevState) +
                    (transitionLogProbabilities[Transition(prevState, curState)] ?: Double.NEGATIVE_INFINITY)
                if (logProbability > maxLogProbability) {
                    maxLogProbability = logProbability
                    maxPrevState = prevState
                    found = true
                }
            }
            result.newMessage[curState] = maxLogProbability + emissionLogProbabilities.getValue(curState)
            if (found) {
                @Suppress("UNCHECKED_CAST")
                val prev = maxPrevState as S
                result.newExtendedStates[curState] = ExtendedState(
                    curState, lastExtendedStates[prev], observation, transitionDescriptors[Transition(prev, curState)],
                )
            }
        }
        return result
    }

    private fun mostLikelyState(): S {
        var result: S? = null
        var found = false
        var maxLogProbability = Double.NEGATIVE_INFINITY
        for ((state, logProbability) in message!!) {
            if (logProbability > maxLogProbability) {
                result = state
                maxLogProbability = logProbability
                found = true
            }
        }
        check(found) // Otherwise an HMM break would have occurred.
        @Suppress("UNCHECKED_CAST")
        return result as S
    }
}
