package dev.greenwave.maps

import android.content.Context
import dev.greenwave.shared.geo.LatLng
import org.json.JSONArray
import java.io.File

/**
 * Every traffic light that was ever on one of the rider's bicycle routes, so the
 * "Ampeldaten" list can show learned cycles outside of a navigation. Worker-only.
 */
internal class KnownLights(context: Context) {
    private val file = File(context.applicationContext.filesDir, "greenwave_lights.json")
    private val lights: LinkedHashSet<LatLng> by lazy { load() }

    fun all(): List<LatLng> = lights.toList()

    fun addAll(positions: Collection<LatLng>) {
        if (lights.addAll(positions)) save()
    }

    private fun load(): LinkedHashSet<LatLng> {
        val result = LinkedHashSet<LatLng>()
        if (!file.exists()) return result
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length() step 2) result.add(LatLng(array.getDouble(i), array.getDouble(i + 1)))
        }
        return result
    }

    private fun save() {
        val array = JSONArray()
        lights.forEach { array.put(it.latitude).put(it.longitude) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }
}
