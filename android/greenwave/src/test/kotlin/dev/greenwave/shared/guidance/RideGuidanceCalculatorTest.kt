package dev.greenwave.shared.guidance

import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.location.LocationUpdate
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideGuidanceCalculatorTest {
    // Same fixture as GreenWaveOptimizerTest: ~1113.2 m due north, one light
    // at the far end, green for the first 10 s of every minute. From the
    // start at t=0 only 13.0 km/h (in 12.5..13.5) arrives on green.
    private val path = listOf(LatLng(52.5200, 13.4050), LatLng(52.5300, 13.4050))
    private val light = TrafficLight(
        position = LatLng(52.5300, 13.4050),
        cycleLengthSec = 60,
        greenStartOffsetSec = 0,
        greenDurationSec = 10,
    )
    private val route = Route(path = path, trafficLights = listOf(light))
    private val range = SpeedRangeKmh(min = 12.5, max = 13.5)

    private fun update(position: LatLng, speedKmh: Double) =
        LocationUpdate(position = position, epochSec = 0L, speedKmh = speedKmh, bearingDegrees = null)

    @Test
    fun combinesRecommendationAdviceAndNextLightDistance() {
        val guidance = RideGuidanceCalculator.compute(route, update(path.first(), speedKmh = 8.0), range)

        assertEquals(13.0, guidance.targetSpeedKmh)
        assertEquals(8.0, guidance.currentSpeedKmh)
        assertEquals(SpeedAdvice.FASTER, guidance.advice)
        val distance = assertNotNull(guidance.nextLightDistanceMeters)
        assertTrue(distance in 1100.0..1125.0, "distance was $distance")
    }

    @Test
    fun holdWhenRidingAtRecommendation() {
        val guidance = RideGuidanceCalculator.compute(route, update(path.first(), speedKmh = 13.5), range)
        assertEquals(SpeedAdvice.HOLD, guidance.advice)
    }

    @Test
    fun noLightsAheadMeansNoTargetAndNoDistance() {
        val guidance = RideGuidanceCalculator.compute(route, update(light.position, speedKmh = 20.0), range)

        assertNull(guidance.targetSpeedKmh)
        assertNull(guidance.nextLightDistanceMeters)
        assertEquals(SpeedAdvice.NONE, guidance.advice)
    }

    @Test
    fun degeneratePathDoesNotCrash() {
        val guidance = RideGuidanceCalculator.compute(
            Route(path = listOf(path.first()), trafficLights = emptyList()),
            update(path.first(), speedKmh = 10.0),
            range,
        )
        assertEquals(SpeedAdvice.NONE, guidance.advice)
    }
}
