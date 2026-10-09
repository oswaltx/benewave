package dev.greenwave.shared.live

import dev.greenwave.shared.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveSignalCycleTest {
    private val here = LatLng(53.5637, 9.9197)

    /** Fixed 75 s cycle: red-amber, green 13 s, amber 3 s, red — like Hamburg LSA 353. */
    private fun fixedCycle(start: Long, cycles: Int, cycle: Long = 75, green: Long = 13) = buildList {
        for (k in 0 until cycles) {
            val t = start + k * cycle
            add(SignalObservation(t - 1, SignalState.RED_AMBER))
            add(SignalObservation(t, SignalState.GREEN))
            add(SignalObservation(t + green, SignalState.AMBER))
            add(SignalObservation(t + green + 3, SignalState.RED))
        }
    }

    @Test
    fun fixedCycleGivesCycleGreenAndPhase() {
        val obs = fixedCycle(start = 1_000_000, cycles = 10)
        val light = assertNotNull(LiveSignalCycle.estimate(here, obs, nowEpochSec = 1_000_000 + 10 * 75))
        assertEquals(75, light.cycleLengthSec)
        assertEquals(13, light.greenDurationSec)
        assertEquals(((1_000_000 + 9 * 75) % 75).toInt(), light.greenStartOffsetSec)
    }

    @Test
    fun demandShiftedGreenInFixedCycleKeepsOnlyTheSafeWindow() {
        // Cycle frame 75 s (resets every 75 s), green starts shifted by 0..8 s, lasts 19 s.
        val base = 1_000_000L
        val shifts = listOf(0L, 8L, 2L, 6L, 1L, 7L, 3L, 5L, 4L, 0L)
        val obs = buildList {
            shifts.forEachIndexed { k, d ->
                val t = base + k * 75 + 10 + d
                add(SignalObservation(t - 1, SignalState.RED_AMBER))
                add(SignalObservation(t, SignalState.GREEN))
                add(SignalObservation(t + 19, SignalState.AMBER))
                add(SignalObservation(t + 22, SignalState.RED))
            }
        }
        val resets = (0 until 10).map { base + it * 75L }
        val light = assertNotNull(LiveSignalCycle.estimate(here, obs, base + 10 * 75, resets))
        assertEquals(75, light.cycleLengthSec)
        // The window must lie inside the actual green of most cycles (quantiles drop the extremes).
        val start = light.greenStartOffsetSec.toLong()
        val end = start + light.greenDurationSec
        val covered = shifts.withIndex().count { (k, d) ->
            val greenStart = (base + k * 75 + 10 + d) % 75
            greenStart <= start && greenStart + 19 >= end
        }
        kotlin.test.assertTrue(covered >= 6, "window $start..$end inside green in only $covered of 10 cycles")
        kotlin.test.assertTrue(light.greenDurationSec in 10..14, "green ${light.greenDurationSec}")
    }

    @Test
    fun actuatedSignalWithVaryingCyclesGivesNull() {
        var t = 1_000_000L
        val obs = buildList {
            for (gap in listOf(60L, 95L, 70L, 120L, 55L, 88L)) {
                add(SignalObservation(t, SignalState.GREEN))
                add(SignalObservation(t + 10, SignalState.RED))
                t += gap
            }
        }
        assertNull(LiveSignalCycle.estimate(here, obs, nowEpochSec = t))
    }

    @Test
    fun programChangeInTheNewestDataFailsTheSelfCheck() {
        // Same 75 s frame, but green moves by 30 s for the last cycles (e.g. a new signal program).
        val base = 1_000_000L
        val obs = buildList {
            for (k in 0 until 12) {
                val t = base + k * 75 + 10 + if (k >= 8) 30 else 0
                add(SignalObservation(t, SignalState.GREEN))
                add(SignalObservation(t + 15, SignalState.RED))
            }
        }
        val resets = (0 until 12).map { base + it * 75L }
        assertNull(LiveSignalCycle.estimate(here, obs, base + 12 * 75, resets))
    }

    @Test
    fun staleDataGivesNull() {
        val obs = fixedCycle(start = 1_000_000, cycles = 10)
        assertNull(LiveSignalCycle.estimate(here, obs, nowEpochSec = 1_000_000 + 30 * 75))
    }

    @Test
    fun tooFewCyclesGivesNull() {
        assertNull(LiveSignalCycle.estimate(here, fixedCycle(1_000_000, 2), 1_000_000 + 150))
    }
}

class LaneConnectionMatcherTest {
    // Straight north-bound path through a junction at ~53.5300.
    private val path = listOf(LatLng(53.5280, 9.9000), LatLng(53.5320, 9.9000))
    private val light = LatLng(53.5300, 9.9000)

    private fun conn(id: String, type: String, stop: LatLng, exit: LatLng) =
        LaneConnection(id, type, stop, exit, emptyList())

    @Test
    fun picksBicycleLaneInRidingDirection() {
        val straightCar = conn("car", "KFZ", LatLng(53.52990, 9.90002), LatLng(53.53020, 9.90002))
        val straightBike = conn("bike", "Radfahrer", LatLng(53.52990, 9.90008), LatLng(53.53020, 9.90008))
        val opposite = conn("opp", "Radfahrer", LatLng(53.53020, 9.90010), LatLng(53.52990, 9.90010))
        val leftTurn = conn("left", "Radfahrer", LatLng(53.52990, 9.90005), LatLng(53.53005, 9.8990))
        val pedestrian = conn("ped", "Fußgänger", LatLng(53.52995, 9.90000), LatLng(53.53000, 9.90000))
        val result = LaneConnectionMatcher.match(path, light, listOf(straightCar, opposite, leftTurn, pedestrian, straightBike))
        assertEquals("bike", result?.id)
    }

    @Test
    fun fallsBackToCarLane() {
        val straightCar = conn("car", "KFZ/Bus", LatLng(53.52990, 9.90002), LatLng(53.53020, 9.90002))
        assertEquals("car", LaneConnectionMatcher.match(path, light, listOf(straightCar))?.id)
    }

    @Test
    fun nothingNearbyGivesNull() {
        val far = conn("far", "Radfahrer", LatLng(53.5400, 9.9000), LatLng(53.5410, 9.9000))
        assertNull(LaneConnectionMatcher.match(path, light, listOf(far)))
    }
}
