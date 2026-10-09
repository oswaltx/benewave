package dev.greenwave.shared.algorithm

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GreenWaveOptimizerTest {
    // A single straight segment, ~1113.2m long (see RouteProgressTest), with
    // one traffic light exactly at the far end: cycle 60s, green for the
    // first 10s of each minute (epoch seconds where epochSec % 60 < 10).
    private val path = listOf(
        LatLng(52.5200, 13.4050),
        LatLng(52.5300, 13.4050),
    )
    private val light = TrafficLight(
        position = LatLng(52.5300, 13.4050),
        cycleLengthSec = 60,
        greenStartOffsetSec = 0,
        greenDurationSec = 10,
    )
    private val route = Route(path = path, trafficLights = listOf(light))

    @Test
    fun picksTheOneSpeedInRangeThatArrivesDuringGreen() {
        // Worked out by hand from the fixed geometry above (see plan):
        // only 13.0 km/h lands the arrival time's cycle-position in [0, 10).
        val result = GreenWaveOptimizer.recommend(
            route = route,
            currentPosition = path.first(),
            nowEpochSec = 0L,
            speedRange = SpeedRangeKmh(min = 12.5, max = 13.5),
            stepKmh = 0.5,
        )

        assertEquals(13.0, result?.speedKmh)
        assertEquals(1, result?.lightsHitGreen)
        assertEquals(1, result?.totalRemainingLights)
    }

    @Test
    fun returnsNullWhenNoTrafficLightsRemainAhead() {
        val result = GreenWaveOptimizer.recommend(
            route = route,
            currentPosition = light.position,
            nowEpochSec = 0L,
            speedRange = SpeedRangeKmh(min = 10.0, max = 25.0),
        )

        assertNull(result)
    }
}
