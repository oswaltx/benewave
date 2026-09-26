package dev.greenwave.shared.data

import dev.greenwave.shared.geo.LatLng
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomTrafficTimingGeneratorTest {
    @Test
    fun generatesPlausibleTimingWithinExpectedRanges() {
        val random = Random(42)

        repeat(100) {
            val light = RandomTrafficTimingGenerator.generate(LatLng(52.52, 13.40), random)

            assertTrue(light.cycleLengthSec in 45..90, "cycleLengthSec was ${light.cycleLengthSec}")
            assertTrue(light.greenDurationSec in 15..30, "greenDurationSec was ${light.greenDurationSec}")
            assertTrue(light.greenDurationSec < light.cycleLengthSec)
            assertTrue(light.greenStartOffsetSec in 0 until light.cycleLengthSec)
        }
    }

    @Test
    fun placesTheGeneratedLightAtTheGivenPosition() {
        val position = LatLng(52.52, 13.40)

        val light = RandomTrafficTimingGenerator.generate(position, Random(1))

        assertTrue(light.position == position)
    }

    @Test
    fun deterministicReturnsSameTimingForSamePosition() {
        val position = LatLng(52.5200, 13.4050)

        val first = RandomTrafficTimingGenerator.deterministic(position)
        val second = RandomTrafficTimingGenerator.deterministic(position)

        assertEquals(first.cycleLengthSec, second.cycleLengthSec)
        assertEquals(first.greenDurationSec, second.greenDurationSec)
        assertEquals(first.greenStartOffsetSec, second.greenStartOffsetSec)
    }

    @Test
    fun deterministicReturnsDifferentTimingForDifferentPosition() {
        val a = RandomTrafficTimingGenerator.deterministic(LatLng(52.5200, 13.4050))
        val b = RandomTrafficTimingGenerator.deterministic(LatLng(52.5210, 13.4050))

        // Positions are 10m apart — different timings almost guaranteed.
        // We allow a rare collision by checking the tuple rather than a
        // single field; in practice no two positions in a real route ever
        // collide.
        assertTrue(
            a.cycleLengthSec != b.cycleLengthSec ||
                a.greenStartOffsetSec != b.greenStartOffsetSec ||
                a.greenDurationSec != b.greenDurationSec,
            "expected different programs for nearby but distinct positions",
        )
    }
}
