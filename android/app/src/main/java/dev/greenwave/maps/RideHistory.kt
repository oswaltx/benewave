package dev.greenwave.maps

import android.content.Context
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.tracking.StopEvent
import dev.greenwave.shared.tracking.StopEventMatcher
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** One recorded ride as the "Meine Fahrten" list shows it. */
internal data class RideSummary(
    val track: File,
    val startEpochSec: Long,
    val durationSec: Long,
    val distanceMeters: Double,
    val movingAvgKmh: Double?,
    val maxKmh: Double?,
    val stops: List<StopEvent>,
    val waitSec: Long,
    val stopsAtLights: Int,
    /** First route the app suggested during the ride, if it was a navigation. */
    val suggestedEtaSec: Int?,
    val suggestedLengthMeters: Int?,
    val reroutes: Int,
    val rating: Int?,
)

/** Reads the track files back for display. Worker-only (file IO). */
internal object RideHistory {
    private const val MAX_ACCURACY_M = 30.0

    fun summaries(context: Context, stops: List<StopEvent>, rides: List<Ride>, lights: List<LatLng>): List<RideSummary> =
        TrackFile.all(context).filter { it.name.endsWith(".csv") }.mapNotNull { summarize(it, stops, rides, lights) }

    private fun summarize(file: File, allStops: List<StopEvent>, rides: List<Ride>, lights: List<LatLng>): RideSummary? {
        val fixes = read(file)
        if (fixes.size < 2) return null
        val start = fixes.first().epochSec
        val end = fixes.last().epochSec
        var distance = 0.0
        var previous: Fix? = null
        for (fix in fixes) {
            if (fix.accuracy != null && fix.accuracy > MAX_ACCURACY_M) continue
            previous?.let { distance += distanceMeters(it.position, fix.position) }
            previous = fix
        }
        val moving = fixes.mapNotNull { it.speedKmh }.filter { it >= RideLog.MOVING_KMH }
        val stops = allStops.filter { it.startEpochSec in start..end }
        val routes = File(file.path.removeSuffix(".csv") + "-routes.json")
            .takeIf { it.exists() }
            ?.let { runCatching { JSONArray(it.readText()) }.getOrNull() }
        val first = routes?.optJSONObject(0)
        return RideSummary(
            track = file,
            startEpochSec = start,
            durationSec = end - start,
            distanceMeters = distance,
            movingAvgKmh = moving.takeIf { it.isNotEmpty() }?.average(),
            maxKmh = moving.maxOrNull(),
            stops = stops,
            waitSec = stops.sumOf { it.durationSec },
            stopsAtLights = stops.count { StopEventMatcher.match(it.position, lights) != null },
            suggestedEtaSec = first?.takeUnless { it.isNull("eta_s") }?.optInt("eta_s"),
            suggestedLengthMeters = first?.optInt("length_m"),
            reroutes = maxOf(0, (routes?.length() ?: 0) - 1),
            rating = rides.firstOrNull { it.startEpochSec in start..end || start in it.startEpochSec..it.endEpochSec }?.rating,
        )
    }

    /** The track as GPX in the cache dir, so CoMaps can import and draw it. */
    fun toGpx(context: Context, summary: RideSummary): File {
        val out = File(context.cacheDir, "greenwave-${summary.track.nameWithoutExtension}.gpx")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        out.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"greenWave Maps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("<trk><name>${trackName(summary)}</name><trkseg>\n")
            for (fix in read(summary.track)) {
                w.write(
                    String.format(
                        Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n",
                        fix.position.latitude, fix.position.longitude, iso.format(Date(fix.epochSec * 1000)),
                    ),
                )
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
        return out
    }

    /** Name of the track (and of the category CoMaps' import creates for it). */
    fun trackName(summary: RideSummary): String = "greenWave ${title(summary)}"

    fun title(summary: RideSummary): String =
        SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN).format(Date(summary.startEpochSec * 1000))

    fun minutes(sec: Long): String = if (sec < 60) "$sec s" else "${(sec / 60.0).roundToInt()} min"

    private class Fix(val epochSec: Long, val position: LatLng, val speedKmh: Double?, val accuracy: Double?)

    private fun read(file: File): List<Fix> = file.useLines { lines ->
        lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 4) return@mapNotNull null
            val epoch = c[0].toLongOrNull() ?: return@mapNotNull null
            val lat = c[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = c[2].toDoubleOrNull() ?: return@mapNotNull null
            Fix(
                epochSec = epoch,
                position = LatLng(latitude = lat, longitude = lon),
                speedKmh = c[3].toDoubleOrNull()?.takeIf { it >= 0 },
                accuracy = c.getOrNull(5)?.toDoubleOrNull(),
            )
        }.toList()
    }
}
