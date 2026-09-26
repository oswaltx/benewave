package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.tracking.StopEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device log of detected stops (the raw material for learning each light's
 * cycle), as one JSON file in the app's private storage. Nothing leaves the
 * device. Capped at [MAX_EVENTS]; the oldest events are dropped first.
 * Not thread-safe — only touch it from GreenWaveController's worker.
 */
internal class StopEventStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "greenwave_stop_events.json")
    private var cache: MutableList<StopEvent>? = null

    fun all(): List<StopEvent> = load()

    fun append(event: StopEvent) {
        val events = load()
        events.add(event)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        save(events)
    }

    private fun load(): MutableList<StopEvent> {
        cache?.let { return it }
        val events = mutableListOf<StopEvent>()
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) events.add(array.getJSONObject(i).toStopEvent())
            } catch (e: Exception) {
                Log.w(TAG, "stop event log unreadable, starting fresh", e)
            }
        }
        cache = events
        return events
    }

    private fun save(events: List<StopEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun StopEvent.toJson() = JSONObject()
        .put("start", startEpochSec)
        .put("end", endEpochSec)
        .put("duration", durationSec)
        .put("lat", position.latitude)
        .put("lon", position.longitude)
        .put("minKmh", minSpeedKmh)
        .put("maxKmh", maxSpeedKmh)

    private fun JSONObject.toStopEvent() = StopEvent(
        startEpochSec = getLong("start"),
        endEpochSec = getLong("end"),
        durationSec = getLong("duration"),
        position = LatLng(latitude = getDouble("lat"), longitude = getDouble("lon")),
        minSpeedKmh = getDouble("minKmh"),
        maxSpeedKmh = getDouble("maxKmh"),
    )

    private companion object {
        const val TAG = "GreenWave"
        const val MAX_EVENTS = 5_000
    }
}
