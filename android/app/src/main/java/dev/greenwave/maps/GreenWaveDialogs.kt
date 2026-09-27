package dev.greenwave.maps

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import app.organicmaps.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.geo.LatLng
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** greenWave's dialogs, built in code so CoMaps' layouts stay untouched. */
internal object GreenWaveDialogs {

    /** Asked once after every logged bicycle navigation. */
    fun showRating(activity: Activity, ride: Ride) {
        val content = column(activity)
        val learned = ride.passedLights.count { it.learned }
        content.addView(text(activity, "${ride.passedLights.size} Ampeln passiert, davon $learned mit gelerntem Zyklus."))
        val stars = RatingBar(activity).apply {
            numStars = 5
            stepSize = 1f
        }
        content.addView(stars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Wie gut war die Tempo-Empfehlung?")
            .setView(content)
            .setPositiveButton("Speichern") { _, _ ->
                GreenWaveController.saveRating(ride, stars.rating.toInt().takeIf { it > 0 }, emptyList())
            }
            .setNegativeButton("Überspringen", null)
        if (ride.passedLights.isNotEmpty()) {
            builder.setNeutralButton("Ampeln markieren…") { _, _ ->
                showWrongLights(activity, ride, stars.rating.toInt().takeIf { it > 0 })
            }
        }
        builder.show()
    }

    private fun showWrongLights(activity: Activity, ride: Ride, rating: Int?) {
        val labels = ride.passedLights.mapIndexed { i, light ->
            val km = "%.1f".format(light.atMeters / 1000)
            "Ampel ${i + 1} · nach $km km" + if (light.learned) " (gelernt)" else ""
        }.toTypedArray()
        val checked = BooleanArray(labels.size)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Welche Ampeln lagen besonders daneben?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Speichern") { _, _ ->
                val wrong: List<LatLng> = ride.passedLights.filterIndexed { i, _ -> checked[i] }.map { it.position }
                GreenWaveController.saveRating(ride, rating, wrong)
            }
            .setNegativeButton("Abbrechen") { _, _ -> GreenWaveController.saveRating(ride, rating, emptyList()) }
            .show()
    }

    /** Entry from CoMaps' main menu. */
    fun showMenu(activity: Activity) {
        val tracking = GreenWaveTrackingService.running
        val tracks = TrackFile.all(activity)
        val items = mutableListOf(
            (if (tracking) "Tracking beenden" else "Tracking starten") to {
                if (tracking) GreenWaveTrackingService.stop(activity) else confirmTracking(activity)
            },
            "Meine Fahrten" to { showRides(activity) },
            "Ampeldaten" to { showLightData(activity) },
            "Einstellungen" to { showSettings(activity) },
        )
        if (tracks.isNotEmpty()) items.add("Aufzeichnungen teilen (${TrackFile.count(activity)})" to { shareTracks(activity, tracks) })
        MaterialAlertDialogBuilder(activity)
            .setTitle("greenWave")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun confirmTracking(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Tracking starten?")
            .setMessage(
                "Zeichnet deine Fahrt auf, auch ohne Navigation und bei ausgeschaltetem Display: GPS-Spur, " +
                    "Stopps an Ampeln und Tempo. Die Daten bleiben auf diesem Gerät. Beenden über das Menü " +
                    "oder die Benachrichtigung.",
            )
            .setPositiveButton("Starten") { _, _ -> GreenWaveTrackingService.start(activity) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** The raw GPS tracks (CSV) via the share sheet, e.g. to a file manager or messenger. */
    private fun shareTracks(activity: Activity, tracks: List<File>) {
        val uris = ArrayList(tracks.map { FileProvider.getUriForFile(activity, BuildConfig.FILE_PROVIDER_AUTHORITY, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/csv")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, "Aufzeichnungen teilen"))
    }

    /** All recorded rides (tracking mode and bicycle navigations), newest first. */
    fun showRides(activity: Activity) {
        GreenWaveController.rideSummaries { rides ->
            if (activity.isFinishing || activity.isDestroyed) return@rideSummaries
            val rows = rides.map { r ->
                val km = "%.1f".format(Locale.GERMAN, r.distanceMeters / 1000)
                val avg = r.movingAvgKmh?.let { " · Ø ${it.roundToInt()} km/h" } ?: ""
                "${RideHistory.title(r)}${if (r.suggestedEtaSec != null || r.suggestedLengthMeters != null) " · Navi" else ""}\n" +
                    "${RideHistory.minutes(r.durationSec)} · $km km$avg · ${r.stops.size} Stopps"
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Meine Fahrten (${rides.size})")
                .apply {
                    if (rows.isEmpty()) setMessage("Noch keine Fahrten. Starte das Tracking oder eine Navigation mit dem Fahrrad-Profil.")
                    else setItems(rows.toTypedArray()) { _, which -> showRide(activity, rides[which]) }
                }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showRide(activity: Activity, r: RideSummary) {
        val lines = mutableListOf<String>()
        lines += "Dauer: ${RideHistory.minutes(r.durationSec)}"
        lines += "Strecke: ${"%.2f".format(Locale.GERMAN, r.distanceMeters / 1000)} km"
        r.movingAvgKmh?.let { lines += "Ø in Bewegung: ${"%.1f".format(Locale.GERMAN, it)} km/h" }
        r.maxKmh?.let { lines += "Höchsttempo: ${it.roundToInt()} km/h" }
        lines += "Stopps: ${r.stops.size}, davon an Ampeln: ${r.stopsAtLights}"
        if (r.stops.isNotEmpty()) lines += "Stehzeit gesamt: ${RideHistory.minutes(r.waitSec)}"
        if (r.suggestedEtaSec != null || r.suggestedLengthMeters != null) {
            lines += ""
            lines += "Vorschlag der Navigation:"
            r.suggestedEtaSec?.let { eta ->
                val diff = r.durationSec - eta
                val sign = if (diff > 0) "langsamer" else "schneller"
                lines += "  ${RideHistory.minutes(eta.toLong())} (du: ${RideHistory.minutes(kotlin.math.abs(diff))} $sign)"
            }
            r.suggestedLengthMeters?.let {
                lines += "  ${"%.2f".format(Locale.GERMAN, it / 1000.0)} km (du: ${"%+.2f".format(Locale.GERMAN, (r.distanceMeters - it) / 1000)} km)"
            }
            if (r.reroutes > 0) lines += "  ${r.reroutes}× neu berechnet (abgewichen)"
        }
        r.rating?.let { lines += "Bewertung: $it von 5" }
        MaterialAlertDialogBuilder(activity)
            .setTitle(RideHistory.title(r))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Auf der Karte", { _, _ -> showOnMap(activity, r) })
            .setNeutralButton("Teilen") { _, _ ->
                shareTracks(activity, listOf(r.track) + listOf(File(r.track.path.removeSuffix(".csv") + "-routes.json")).filter { it.exists() })
            }
            .setNegativeButton("Zurück") { _, _ -> showRides(activity) }
            .show()
    }

    /** Hands the track as GPX to CoMaps' own import, which draws it and keeps it under "Lesezeichen und Tracks". */
    private fun showOnMap(activity: Activity, r: RideSummary) {
        val gpx = RideHistory.toGpx(activity, r)
        val uri = FileProvider.getUriForFile(activity, BuildConfig.FILE_PROVIDER_AUTHORITY, gpx)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/gpx+xml")
            .setPackage(activity.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Karten-Import nicht gefunden, bitte „Teilen“ verwenden.", Toast.LENGTH_LONG).show()
        }
    }

    /** Everything learned so far, one line per light. */
    fun showLightData(activity: Activity) {
        GreenWaveController.lightStats { stats ->
            if (activity.isFinishing || activity.isDestroyed) return@lightStats
            val learned = stats.count { it.trusted }
            val rows = stats.map { s ->
                val where = "%.5f, %.5f".format(s.position.latitude, s.position.longitude)
                val what = when {
                    s.trusted -> "Zyklus ${s.cycleSec} s, grün ${s.greenSec} s, sicher ${(s.confidence!! * 100).roundToInt()} %"
                    s.cycleSec != null -> "vorläufig ${s.cycleSec} s (sicher ${(s.confidence!! * 100).roundToInt()} %)"
                    s.stops + s.passes > 0 -> "noch zu wenig Daten"
                    else -> "noch keine Daten"
                }
                "${if (s.trusted) "●" else "○"} $what · ${s.stops} Stopps, ${s.passes} Durchfahrten\n$where"
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Ampeldaten: $learned von ${stats.size} gelernt")
                .apply {
                    if (rows.isEmpty()) setMessage("Noch keine Ampeln. Starte eine Navigation mit dem Fahrrad-Profil oder das Tracking.")
                    else setItems(rows.toTypedArray(), null)
                }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Opened by a long press on the navigation panel. */
    fun showSettings(context: Context) {
        val settings = GreenWaveController.settings() ?: return
        val content = column(context)
        val range = settings.manualRange

        content.addView(text(context, "Tempobereich für die Empfehlung (km/h)"))
        val min = number(context, range.min)
        val max = number(context, range.max)
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(min, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = "  bis  " })
            addView(max, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        val logging = SwitchCompat(context).apply {
            text = "Fahrdaten aufzeichnen"
            isChecked = settings.loggingEnabled
        }
        content.addView(logging)
        content.addView(text(context,
            "Stopps und Fahrten während der Fahrrad-Navigation, nur auf diesem Gerät. Daraus lernt die App " +
                "die Ampelzyklen. Bisher: ${GreenWaveController.loggedStops} Stopps, " +
                "${GreenWaveController.loggedRides} Fahrten.", small = true))

        val current = GreenWaveController.speedRange
        val auto = SwitchCompat(context).apply {
            text = "Fahrprofil automatisch bestimmen"
            isChecked = settings.autoProfile
        }
        content.addView(auto)
        content.addView(text(context,
            "Aus deinen letzten Fahrten (typisches Tempo). Aktuell verwendet: " +
                "${current.min.roundToInt()}–${current.max.roundToInt()} km/h. Braucht ein paar Fahrten, " +
                "bis dahin gilt der Bereich oben.", small = true))

        val red = SwitchCompat(context).apply {
            text = "Ich fahre öfter bei Rot"
            isChecked = settings.runsRed
        }
        content.addView(red)
        content.addView(text(context,
            "Deine Fahrten werden dann markiert und später nicht für gemeinsame Ampeldaten verwendet, " +
                "damit sie die Grünphasen nicht verfälschen.", small = true))

        content.addView(text(context,
            "Live-Ampeldaten Hamburg: Freie und Hansestadt Hamburg, Datenlizenz Deutschland " +
                "Namensnennung 2.0 (tld.iot.hamburg.de). Ampelstandorte: © OpenStreetMap-Mitwirkende.", small = true))

        MaterialAlertDialogBuilder(context)
            .setTitle("greenWave")
            .setView(content)
            .setPositiveButton("Speichern") { _, _ ->
                val lo = min.text.toString().toDoubleOrNull()
                val hi = max.text.toString().toDoubleOrNull()
                if (lo != null && hi != null && lo >= 5 && hi <= 45 && hi - lo >= 3) {
                    settings.manualRange = SpeedRangeKmh(min = lo, max = hi)
                }
                settings.loggingEnabled = logging.isChecked
                settings.autoProfile = auto.isChecked
                settings.runsRed = red.isChecked
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 20f)
        setPadding(pad, dp(context, 8f), pad, 0)
    }

    private fun text(context: Context, value: String, small: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 14f)
        setPadding(0, dp(context, 6f), 0, dp(context, 6f))
    }

    private fun number(context: Context, value: Double) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.roundToInt().toString())
        gravity = Gravity.CENTER
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).roundToInt()
}
