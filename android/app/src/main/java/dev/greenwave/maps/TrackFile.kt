package dev.greenwave.maps

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * Raw GPS track of one ride (tracking mode or bicycle navigation) as CSV in
 * filesDir/greenwave_tracks, one file per ride (named by its start time), plus
 * the suggested routes next to it. Worker-only.
 */
internal class TrackFile(context: Context, startEpochSec: Long) {
    val file: File = File(dir(context), "track-$startEpochSec.csv")
    private val writer: Writer = file.bufferedWriter().apply { write(HEADER) }
    private var pending = 0
    private val routesFile = File(file.path.removeSuffix(".csv") + "-routes.json")
    private val routes = JSONArray()

    /** A route the app suggested during this ride (one entry per reroute), for comparing with the track. */
    fun addRoute(route: JSONObject) {
        routes.put(route)
        val tmp = File(routesFile.path + ".tmp")
        tmp.writeText(routes.toString())
        tmp.renameTo(routesFile)
    }

    fun append(location: Location) {
        writer.write(
            String.format(
                Locale.ROOT, "%d,%.7f,%.7f,%.1f,%s,%s,%s\n",
                location.time / 1000, location.latitude, location.longitude,
                if (location.hasSpeed()) location.speed * 3.6 else -1.0,
                if (location.hasBearing()) "%.0f".format(Locale.ROOT, location.bearing) else "",
                if (location.hasAccuracy()) "%.0f".format(Locale.ROOT, location.accuracy) else "",
                if (location.hasAltitude()) "%.0f".format(Locale.ROOT, location.altitude) else "",
            ),
        )
        // Flush every ~30 fixes so a killed process loses at most half a minute.
        if (++pending >= 30) {
            writer.flush()
            pending = 0
        }
    }

    fun close() = runCatching { writer.close() }

    companion object {
        private const val HEADER = "epoch_s,lat,lon,speed_kmh,bearing_deg,accuracy_m,altitude_m\n"

        fun dir(context: Context) = File(context.applicationContext.filesDir, "greenwave_tracks").apply { mkdirs() }

        /** Tracks (CSV) and the routes suggested during them (JSON), newest first. */
        fun all(context: Context): List<File> =
            dir(context).listFiles { f -> f.name.endsWith(".csv") || f.name.endsWith(".json") }
                .orEmpty().sortedByDescending { it.name }

        fun count(context: Context): Int = dir(context).listFiles { f -> f.name.endsWith(".csv") }?.size ?: 0
    }
}
