package dev.greenwave.maps

import android.content.Context
import android.util.Log
import dev.greenwave.shared.calibration.Calibration
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.sharing.SharedEvents
import dev.greenwave.shared.tracking.LightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manual light calibrations (Nerd-Modus), kept apart from [LightEventStore] so they
 * never go to the shared board ([ShareWorker] only reads the own ride events).
 * One JSON array of sessions; part of the ZIP export (file name starts with
 * "greenwave"). Worker-only.
 */
internal class CalibrationStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE)
    private val sessions: MutableList<Calibration.Session> by lazy { load() }

    fun all(): List<Calibration.Session> = sessions.toList()

    fun add(session: Calibration.Session) {
        sessions.add(session)
        save()
    }

    fun clear() {
        sessions.clear()
        file.delete()
    }

    /** Calibrations of the light at [position] (within [SharedEvents.MATCH_METERS]). */
    fun near(position: LatLng): List<Calibration.Session> =
        sessions.filter { distanceMeters(it.light, position) <= SharedEvents.MATCH_METERS }

    /** One row per tap, for a spreadsheet. */
    fun toCsv(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        val out = StringBuilder("messung,start,lat,lon,richtung_grad,abbiegen,radweg,nr,wechsel,zeit_ms,ortszeit,sek_seit_vorigem\n")
        sessions.forEachIndexed { s, session ->
            session.taps.forEachIndexed { i, tap ->
                val previous = session.taps.getOrNull(i - 1)
                out.append(listOf(
                    s + 1,
                    time.format(Date(session.startEpochMs)),
                    session.light.latitude,
                    session.light.longitude,
                    session.headingDeg ?: "",
                    session.turn?.name ?: "",
                    session.sideway ?: "",
                    i + 1,
                    if (tap.change == Calibration.Change.GREEN) "gruen" else "rot",
                    session.epochMs(tap),
                    time.format(Date(session.epochMs(tap))),
                    previous?.let { "%.1f".format(Locale.ROOT, (tap.atMs - it.atMs) / 1000.0) } ?: "",
                ).joinToString(",")).append('\n')
            }
        }
        return out.toString()
    }

    private fun load(): MutableList<Calibration.Session> {
        val result = mutableListOf<Calibration.Session>()
        if (!file.exists()) return result
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) result.add(fromJson(array.getJSONObject(i)))
        } catch (e: Exception) {
            Log.w("GreenWave", "calibrations unreadable, starting fresh", e)
        }
        return result
    }

    private fun save() {
        val array = JSONArray()
        sessions.forEach { array.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) Log.w("GreenWave", "calibrations not saved")
    }

    private companion object {
        const val FILE = "greenwave_calibrations.json"

        fun toJson(s: Calibration.Session): JSONObject = JSONObject()
            .put("lat", s.light.latitude)
            .put("lon", s.light.longitude)
            .put("heading", s.headingDeg ?: JSONObject.NULL)
            .put("turn", s.turn?.name ?: JSONObject.NULL)
            .put("start_ms", s.startEpochMs)
            .put("sideway", s.sideway ?: JSONObject.NULL)
            .put("taps", JSONArray().apply { s.taps.forEach { put(JSONArray().put(it.atMs).put(it.change.name)) } })

        fun fromJson(o: JSONObject): Calibration.Session {
            val taps = o.getJSONArray("taps")
            return Calibration.Session(
                light = LatLng(o.getDouble("lat"), o.getDouble("lon")),
                headingDeg = if (o.isNull("heading")) null else o.getInt("heading"),
                turn = if (o.isNull("turn")) null else LightEvent.Turn.valueOf(o.getString("turn")),
                startEpochMs = o.getLong("start_ms"),
                sideway = if (o.isNull("sideway")) null else o.getBoolean("sideway"),
                taps = (0 until taps.length()).map {
                    val t = taps.getJSONArray(it)
                    Calibration.Tap(t.getLong(0), Calibration.Change.valueOf(t.getString(1)))
                },
            )
        }
    }
}
