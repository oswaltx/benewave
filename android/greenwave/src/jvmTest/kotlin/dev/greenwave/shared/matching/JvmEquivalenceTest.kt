package dev.greenwave.shared.matching

import dev.greenwave.shared.geo.degToRad
import dev.greenwave.shared.geo.sixDecimals
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The KMP replacements must behave exactly like the JVM classes they replace. */
class JvmEquivalenceTest {
    @Test
    fun minHeapPollsInPriorityQueueOrderIncludingTies() {
        val random = Random(7)
        repeat(200) {
            val heap = MinHeap<Pair<Double, Int>>(compareBy { it.first })
            val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            var id = 0
            repeat(300) {
                if (random.nextInt(3) > 0 || !queue.isNotEmpty()) {
                    val x = random.nextInt(20).toDouble() to id++ // few keys: many ties
                    heap.add(x)
                    queue.add(x)
                } else {
                    assertEquals(queue.poll(), heap.poll())
                }
            }
            while (queue.isNotEmpty()) assertEquals(queue.poll(), heap.poll())
            assertEquals(false, heap.isNotEmpty())
        }
    }

    @Test
    fun sixDecimalsMatchesStringFormat() {
        val random = Random(11)
        val values = listOf(0.0, -0.0, 1.0, -1.0, 53.5511, 9.9937, -0.0000004, -0.0000006, 0.0000005, 179.9999995, -33.123456789) +
            List(5000) { random.nextDouble(-180.0, 180.0) }
        for (v in values) assertEquals("%.6f".format(java.util.Locale.ROOT, v), sixDecimals(v), "$v")
    }

    @Test
    fun degToRadMatchesMathToRadians() {
        val random = Random(13)
        for (v in List(5000) { random.nextDouble(-180.0, 180.0) } + listOf(0.0, 90.0, 53.5511)) {
            assertEquals(Math.toRadians(v), degToRad(v), "$v")
        }
    }
}
