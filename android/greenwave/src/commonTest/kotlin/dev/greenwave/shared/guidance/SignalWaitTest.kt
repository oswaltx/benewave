package dev.greenwave.shared.guidance

import dev.greenwave.shared.data.RandomTrafficTimingGenerator
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.model.Route
import dev.greenwave.shared.model.TrafficLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SignalWaitTest {
    private val start = LatLng(53.55, 10.0)
    private fun north(m: Double) = LatLng(start.latitude + m / 111_195.0, start.longitude)
    private val path = listOf(start, north(3_000.0))
    private fun light(m: Double, cycle: Int = 90, green: Int = 30) = TrafficLight(north(m), cycle, 0, green)

    @Test
    fun knownLightWaitsHalfTheRedTimesItsShare() {
        // 60 s red of 90 s: red with p = 2/3, then 30 s on average.
        assertEquals(20.0, SignalWait.expectedWaitSec(light(0.0, cycle = 90, green = 30)), 1e-9)
        assertEquals(0.0, SignalWait.expectedWaitSec(light(0.0, cycle = 60, green = 60)), 1e-9)
        assertEquals(30.0, SignalWait.expectedWaitSec(light(0.0, cycle = 60, green = 0)), 1e-9)
        assertEquals(SignalWait.DEFAULT_WAIT_SEC, SignalWait.expectedWaitSec(light(0.0, cycle = 0)), 1e-9)
        assertEquals(30.0, SignalWait.expectedWaitSec(light(0.0, cycle = 60, green = -5)), 1e-9)
    }

    @Test
    fun unknownLightCountsTheDefaultNotItsPlaceholder() {
        val placeholder = RandomTrafficTimingGenerator.deterministic(north(500.0))
        assertNotEquals(SignalWait.DEFAULT_WAIT_SEC, SignalWait.expectedWaitSec(placeholder))
        val waits = SignalWait.along(Route(path, listOf(placeholder)), known = emptySet())
        assertEquals(1, waits.size)
        assertEquals(500.0, waits[0].alongMeters, 1.0)
        assertEquals(SignalWait.DEFAULT_WAIT_SEC, waits[0].waitSec, 1e-9)
        assertEquals(false, waits[0].known)
    }

    @Test
    fun oneWaitPerIntersectionPreferringTheKnownLight() {
        val unknown = light(1_000.0, green = 80)
        val known = light(1_030.0, cycle = 90, green = 30)
        val waits = SignalWait.along(Route(path, listOf(unknown, known)), known = setOf(known.position))
        assertEquals(1, waits.size)
        assertEquals(20.0, waits[0].waitSec, 1e-9)
        assertEquals(true, waits[0].known)
    }

    @Test
    fun onlyLightsAheadCount() {
        val a = light(300.0, cycle = 90, green = 30)
        val b = light(1_500.0)
        val waits = SignalWait.along(Route(path, listOf(b, a)), known = setOf(a.position))
        val all = SignalWait.ahead(waits, progressMeters = 0.0)
        assertEquals(SignalWait.Summary(20.0 + SignalWait.DEFAULT_WAIT_SEC, lights = 2, known = 1), all)
        val later = SignalWait.ahead(waits, progressMeters = 1_000.0)
        assertEquals(SignalWait.Summary(SignalWait.DEFAULT_WAIT_SEC, lights = 1, known = 0), later)
        assertEquals(SignalWait.Summary(0.0, 0, 0), SignalWait.ahead(waits, progressMeters = 5_000.0))
    }
}
