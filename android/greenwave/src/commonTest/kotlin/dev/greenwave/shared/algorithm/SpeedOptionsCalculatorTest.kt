package dev.greenwave.shared.algorithm

import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedOptionsCalculatorTest {
    // ~1113.2 m straight, one light at the end: 60 s cycle, green 0–30 s.
    private val path = listOf(LatLng(52.5200, 13.4050), LatLng(52.5300, 13.4050))
    private val light = TrafficLight(position = path.last(), cycleLengthSec = 60, greenStartOffsetSec = 0, greenDurationSec = 30)
    private val route = Route(path = path, trafficLights = listOf(light))
    private val range = SpeedRangeKmh(min = 10.0, max = 30.0)

    private fun arrivalPos(speedKmh: Double): Long = ((1113.2 / (speedKmh / 3.6)).toLong()) % 60

    @Test
    fun offersOneFasterAndOneSlowerSpeedWhenCurrentArrivesOnRed() {
        // 17 km/h arrives after ~235 s (pos 55, red); greens lie at ~15–16.5 and ~19.5–22 km/h.
        val current = 17.0
        assertTrue(arrivalPos(current) in 30 until 60)
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, current, range))
        assertNull(options.holdKmh)
        val faster = assertNotNull(options.fasterKmh)
        val slower = assertNotNull(options.slowerKmh)
        assertTrue(faster > current && slower < current, "faster=$faster slower=$slower current=$current")
        assertTrue(arrivalPos(faster) in 2 until 27, "faster arrives at ${arrivalPos(faster)}")
        assertTrue(arrivalPos(slower) in 2 until 27, "slower arrives at ${arrivalPos(slower)}")
    }

    @Test
    fun holdsWhenCurrentSpeedAlreadyArrivesOnGreen() {
        val current = (100..300).map { it / 10.0 }.first { arrivalPos(it) in 10..15 }
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, current, range))
        val hold = assertNotNull(options.holdKmh)
        assertTrue(arrivalPos(hold) in 2 until 27)
    }

    @Test
    fun picksTheLeastChangeInsideABand() {
        val current = 17.0
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, current, range))
        assertEquals(19.5, options.fasterKmh)
        assertEquals(16.5, options.slowerKmh)
        // Faster option is the slowest speed of its band, slower the fastest of its band.
        assertTrue(arrivalPos(options.fasterKmh!! - 0.5) !in 2 until 27 || options.fasterKmh!! - 0.5 <= current)
        assertTrue(arrivalPos(options.slowerKmh!! + 0.5) !in 2 until 27 || options.slowerKmh!! + 0.5 >= current)
    }

    @Test
    fun noLightAheadGivesNull() {
        assertNull(SpeedOptionsCalculator.compute(route, path.last(), 0L, 20.0, range))
    }

    @Test
    fun holdStillOffersTheNeighbouringGreens() {
        // 20.5 km/h is inside the 19.5–22 band; 16.5 (later green) and ~27.5 (earlier green) are the neighbours.
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, 20.5, range))
        assertNotNull(options.holdKmh)
        val slower = assertNotNull(options.slowerKmh)
        val faster = assertNotNull(options.fasterKmh)
        assertTrue(slower < 19.5 && faster > 22.0, "slower=$slower faster=$faster")
        assertTrue(!options.slowerOutOfRange && !options.fasterOutOfRange)
    }

    @Test
    fun sideWithoutBandInRangeFallsBackToOuterRangeFlagged() {
        // Range 10–18: the faster band (19.5–22) lies above it, so it comes from the outer range.
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, 17.0, SpeedRangeKmh(10.0, 18.0)))
        assertEquals(16.5, options.slowerKmh)
        assertTrue(!options.slowerOutOfRange)
        assertEquals(19.5, options.fasterKmh)
        assertTrue(options.fasterOutOfRange)
    }

    @Test
    fun bandInsideTheRangeWinsOverACloserOneOutside() {
        // Range 18–30 at 17 km/h (below it): the in-range 19.5 band is the faster option, never an outer one.
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, 17.0, SpeedRangeKmh(18.0, 30.0)))
        assertEquals(19.5, options.fasterKmh)
        assertTrue(!options.fasterOutOfRange)
        // The slower side has no band in 18–30 below 17, so it is the outer 15–16.5 band.
        assertEquals(16.5, options.slowerKmh)
        assertTrue(options.slowerOutOfRange)
    }

    @Test
    fun edgeOfRangeHasNoOptionBeyondIt() {
        val options = assertNotNull(SpeedOptionsCalculator.compute(route, path.first(), 0L, 30.0, SpeedRangeKmh(10.0, 30.0)))
        assertEquals(null, options.fasterKmh)
    }
}
