package dev.greenwave.maps

import android.content.Context
import android.location.Location
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * Raw GPS track of one tracking-mode ride as CSV in filesDir/greenwave_tracks,
 * one file per ride (named by its start time). Worker-only.
 */
internal class TrackFile(context: Context, startEpochSec: Long) {
    val file: File = File(dir(context), "track-$startEpochSec.csv")
    private val writer: Writer = file.bufferedWriter().apply { write(HEADER) }
    private var pending = 0

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

        fun all(context: Context): List<File> =
            dir(context).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedByDescending { it.name }
    }
}
