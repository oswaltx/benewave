package dev.greenwave.shared.matching

/**
 * Binary min-heap for Kotlin Multiplatform (java.util.PriorityQueue is JVM-only).
 * Sift-up and sift-down follow PriorityQueue, so equal keys come out in the same order.
 */
internal class MinHeap<T>(private val comparator: Comparator<in T>) {
    private val items = ArrayList<T>()

    fun isNotEmpty() = items.isNotEmpty()

    fun add(x: T) {
        items.add(x)
        var k = items.size - 1
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val e = items[parent]
            if (comparator.compare(x, e) >= 0) break
            items[k] = e
            k = parent
        }
        items[k] = x
    }

    fun poll(): T {
        val result = items[0]
        val x = items.removeAt(items.size - 1)
        val n = items.size
        if (n > 0) {
            var k = 0
            val half = n ushr 1
            while (k < half) {
                var child = 2 * k + 1
                var c = items[child]
                val right = child + 1
                if (right < n && comparator.compare(c, items[right]) > 0) {
                    child = right
                    c = items[child]
                }
                if (comparator.compare(x, c) <= 0) break
                items[k] = c
                k = child
            }
            items[k] = x
        }
        return result
    }
}
