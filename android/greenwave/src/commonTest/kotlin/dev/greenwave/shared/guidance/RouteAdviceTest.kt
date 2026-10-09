package dev.greenwave.shared.guidance

import dev.greenwave.shared.algorithm.SpeedOptionsCalculator
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.tracking.RhythmEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The invariant: speed advice only from live or learned lights (confidence ≥ 0.5). */
class RouteAdviceTest {
    private val start = LatLng(53.55, 10.0)
    private fun north(m: Double) = LatLng(start.latitude + m / 111_195.0, start.longitude)
    private val path = listOf(start, north(3_000.0))
    private val range = SpeedRangeKmh(min = 10.0, max = 25.0)
    private val here = LocationUpdate(start, epochSec = 1_760_000_000L, speedKmh = 18.0, bearingDegrees = 0f)

    private fun light(m: Double, offset: Int = 0) = TrafficLight(north(m), cycleLengthSec = 90, greenStartOffsetSec = offset, greenDurationSec = 30)
    private fun estimate(confidence: Double) = RhythmEstimate(90, 10, 30, sampleCount = 6, confidence = confidence)

    @Test
    fun resolveTrustsOnlyConfidentOrLivePrograms() {
        val a = north(300.0); val b = north(600.0); val c = north(900.0)
        val live = light(900.0, offset = 42)
        val resolved = RouteAdvice.resolve(listOf(a, b, c), mapOf(c to live)) { p ->
            when (p) { a -> estimate(0.49); b -> estimate(0.5); else -> estimate(1.0) }
        }
        assertEquals(setOf(b, c), resolved.known)
        assertEquals(RandomTrafficTimingGenerator.deterministic(a), resolved.lights[0])
        assertEquals(TrafficLight(b, 90, 10, 30), resolved.lights[1])
        assertEquals(live, resolved.lights[2])
    }

    @Test
    fun unknownNextLightGivesNoSpeed() {
        val route = Route(path, listOf(light(300.0), light(600.0)))
        val advice = RouteAdvice.advice(route, known = setOf(north(600.0)), update = here, speedRange = range)
        assertFalse(advice.nextLightKnown)
        assertNull(advice.options)
        assertEquals(300.0, advice.nextLightDistanceMeters!!, 1.0)
    }

    @Test
    fun unknownLightFurtherOnDoesNotSteerTheSpeed() {
        val first = light(300.0); val unknown = light(600.0, offset = 45); val third = light(900.0, offset = 20)
        val route = Route(path, listOf(first, unknown, third))
        val advice = RouteAdvice.advice(route, known = setOf(first.position, third.position), update = here, speedRange = range)
        assertTrue(advice.nextLightKnown)
        val onlyFirst = SpeedOptionsCalculator.compute(route.copy(trafficLights = listOf(first)), here.position, here.epochSec, here.speedKmh, range)
        assertEquals(onlyFirst, advice.options)
    }

    @Test
    fun knownLightRepresentsItsIntersection() {
        val sibling = light(300.0, offset = 45); val known = light(330.0)
        val advice = RouteAdvice.advice(Route(path, listOf(sibling, known)), setOf(known.position), here, range)
        assertTrue(advice.nextLightKnown)
        assertNotNull(advice.options)
        assertEquals(330.0, advice.nextLightDistanceMeters!!, 1.0)
    }

    @Test
    fun lightsBeyondLookaheadAndBehindAreIgnored() {
        val far = light(2_500.0)
        val advice = RouteAdvice.advice(Route(path, listOf(far)), setOf(far.position), here, range)
        assertEquals(RouteAdvice.Advice(null, false, null), advice)
        val past = RouteAdvice.advice(Route(path, listOf(light(300.0))), setOf(north(300.0)), here.copy(position = north(400.0)), range)
        assertEquals(RouteAdvice.Advice(null, false, null), past)
    }

    @Test
    fun liveWinsEvenOverAnUnconfidentEstimateWithoutAskingForIt() {
        val a = north(300.0)
        val live = light(300.0, offset = 42)
        val resolved = RouteAdvice.resolve(listOf(a), mapOf(a to live)) { error("estimate asked for a live light") }
        assertEquals(setOf(a), resolved.known)
        assertEquals(listOf(live), resolved.lights)
    }

    @Test
    fun emptyInputsGiveNoAdvice() {
        val none = RouteAdvice.Advice(null, false, null)
        assertTrue(RouteAdvice.resolve(emptyList(), emptyMap()) { null }.lights.isEmpty())
        assertEquals(none, RouteAdvice.advice(Route(path, emptyList()), emptySet(), here, range))
        val unknownOnly = RouteAdvice.advice(Route(path, listOf(light(300.0), light(600.0))), emptySet(), here, range)
        assertFalse(unknownOnly.nextLightKnown)
        assertNull(unknownOnly.options)
    }

    @Test
    fun unknownSiblingThenKnownThenUnknownFurtherOn() {
        val sibling = light(300.0, offset = 45); val known = light(320.0); val unknown = light(700.0, offset = 60)
        val route = Route(path, listOf(sibling, known, unknown))
        val advice = RouteAdvice.advice(route, setOf(known.position), here, range)
        assertTrue(advice.nextLightKnown)
        val onlyKnown = SpeedOptionsCalculator.compute(route.copy(trafficLights = listOf(known)), here.position, here.epochSec, here.speedKmh, range)
        assertEquals(onlyKnown, advice.options)
    }
}
