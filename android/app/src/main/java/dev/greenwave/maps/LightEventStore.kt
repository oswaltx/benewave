package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.sharing.SharedEvents
import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device log of [LightEvent]s (stop start/end and pass-throughs per light and
 * direction), the input for learning each light's program. One compact JSON
 * array of rows `[lat, lon, epochSec, kind, heading|null, exitHeading|null, sideway|null]`
 * (older files lack the last columns). Capped at
 * [MAX_EVENTS], oldest dropped first. Worker-only.
 *
 * The same format holds the events other riders shared ([SHARED_FILE], written
 * by [ShareWorker] through its own instance); with [watch] an instance notices
 * when the file was replaced by another one and reads it again.
 */
internal class LightEventStore(
    context: Context,
    fileName: String = OWN_FILE,
    private val watch: Boolean = false,
) {
    private val file = File(context.applicationContext.filesDir, fileName)
    private var cache: MutableList<LightEvent>? = null
    private var loadedModified = 0L

    fun all(): List<LightEvent> = load()

    fun addAll(events: Collection<LightEvent>) {
        if (events.isEmpty()) return
        val list = load()
        list.addAll(events)
        while (list.size > MAX_EVENTS) list.removeAt(0)
        save(list)
    }

    /** Replaces the whole log (the shared events after a download). */
    fun replaceAll(events: List<LightEvent>) {
        val list = events.takeLast(MAX_EVENTS).toMutableList()
        save(list)
        cache = list
    }

    /** Events at [light] (same OSM position). */
    fun at(light: LatLng): List<LightEvent> = load().filter { it.light == light }

    /** Events at the light at [position] within [SharedEvents.MATCH_METERS] (others' positions differ in the last digits). */
    fun near(position: LatLng): List<LightEvent> = SharedEvents.near(load(), position)

    private fun load(): MutableList<LightEvent> {
        if (watch && cache != null && file.lastModified() != loadedModified) cache = null
        cache?.let { return it }
        val events = mutableListOf<LightEvent>()
        loadedModified = file.lastModified()
        if (file.exists()) {
            try {
                val rows = JSONArray(file.readText())
                for (i in 0 until rows.length()) events.add(fromRow(rows.getJSONArray(i)))
            } catch (e: Exception) {
                Log.w("GreenWave", "light event log ${file.name} unreadable, starting fresh", e)
            }
        }
        cache = events
        return events
    }

    private fun save(events: List<LightEvent>) {
        val rows = JSONArray()
        events.forEach { rows.put(toRow(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(rows.toString())
        tmp.renameTo(file)
        loadedModified = file.lastModified()
    }

    companion object {
        const val OWN_FILE = "greenwave_light_events.json"
        const val SHARED_FILE = "greenwave_shared_events.json"
        private const val MAX_EVENTS = 20_000

        fun toRow(e: LightEvent): JSONArray =
            JSONArray().put(e.light.latitude).put(e.light.longitude).put(e.epochSec).put(e.kind.name)
                .put(e.headingDeg ?: JSONObject.NULL)
                .put(e.exitHeadingDeg ?: JSONObject.NULL)
                .put(e.sideway ?: JSONObject.NULL)

        /** One row; throws on malformed input (callers decide whether to skip or give up). */
        fun fromRow(r: JSONArray): LightEvent = LightEvent(
            light = LatLng(latitude = r.getDouble(0), longitude = r.getDouble(1)),
            epochSec = r.getLong(2),
            kind = LightEvent.Kind.valueOf(r.getString(3)),
            headingDeg = if (r.isNull(4)) null else r.getInt(4),
            exitHeadingDeg = if (r.length() < 6 || r.isNull(5)) null else r.getInt(5),
            sideway = if (r.length() < 7 || r.isNull(6)) null else r.getBoolean(6),
        )
    }
}
