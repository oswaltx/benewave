package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import java.io.File

/**
 * On-device log of [LightEvent]s (stop start/end and pass-throughs per light and
 * direction), the input for learning each light's program. One compact JSON
 * array of rows `[lat, lon, epochSec, kind, heading|null, exitHeading|null]`
 * (older files have no exit column). Capped at
 * [MAX_EVENTS], oldest dropped first. Worker-only.
 */
internal class LightEventStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "greenwave_light_events.json")
    private var cache: MutableList<LightEvent>? = null

    fun all(): List<LightEvent> = load()

    fun addAll(events: Collection<LightEvent>) {
        if (events.isEmpty()) return
        val list = load()
        list.addAll(events)
        while (list.size > MAX_EVENTS) list.removeAt(0)
        save(list)
    }

    /** Events at [light] (same OSM position). */
    fun at(light: LatLng): List<LightEvent> = load().filter { it.light == light }

    private fun load(): MutableList<LightEvent> {
        cache?.let { return it }
        val events = mutableListOf<LightEvent>()
        if (file.exists()) {
            try {
                val rows = JSONArray(file.readText())
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONArray(i)
                    events.add(
                        LightEvent(
                            light = LatLng(latitude = r.getDouble(0), longitude = r.getDouble(1)),
                            epochSec = r.getLong(2),
                            kind = LightEvent.Kind.valueOf(r.getString(3)),
                            headingDeg = if (r.isNull(4)) null else r.getInt(4),
                            exitHeadingDeg = if (r.length() < 6 || r.isNull(5)) null else r.getInt(5),
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w("GreenWave", "light event log unreadable, starting fresh", e)
            }
        }
        cache = events
        return events
    }

    private fun save(events: List<LightEvent>) {
        val rows = JSONArray()
        events.forEach { e ->
            rows.put(
                JSONArray().put(e.light.latitude).put(e.light.longitude).put(e.epochSec).put(e.kind.name)
                    .put(e.headingDeg ?: org.json.JSONObject.NULL)
                    .put(e.exitHeadingDeg ?: org.json.JSONObject.NULL),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(rows.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX_EVENTS = 20_000
    }
}
