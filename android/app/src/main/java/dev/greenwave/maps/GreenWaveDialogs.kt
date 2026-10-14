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
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.routing.RoutingController
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
            "Routenmodus: ${GreenWaveController.settings()?.routeMode?.label ?: RouteMode.STANDARD.label}" to {
                showRouteMode(activity)
            },
            "Einstellungen" to { showSettings(activity) },
        )
        if (GreenWaveController.settings()?.nerdMode == true) {
            items.add(3, "Ampel kalibrieren" to { CalibrationDialogs.showMenu(activity) })
        }
        if (tracks.isNotEmpty()) items.add("Aufzeichnungen teilen (${TrackFile.count(activity)})" to { shareTracks(activity, tracks) })
        items.add("Daten exportieren" to { exportData(activity) })
        items.add("Gemeinsam lernen" to { showSharing(activity) })
        MaterialAlertDialogBuilder(activity)
            .setTitle("greenWave")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun showRouteMode(activity: Activity) {
        val settings = GreenWaveController.settings() ?: return
        val modes = RouteMode.entries
        val rows = modes.map { "${it.label}\n${it.description}" }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Routenmodus (Fahrrad)")
            .setSingleChoiceItems(rows, modes.indexOf(settings.routeMode)) { dialog, which ->
                val mode = modes[which]
                settings.routeMode = mode
                mode.activate()
                dialog.dismiss()
                val routing = RoutingController.get()
                val bicycle = routing.lastRouterType == Router.Bicycle
                if (bicycle && routing.isPlanning && routing.isBuilt && !routing.isNavigating) {
                    routing.rebuildLastRoute()
                } else {
                    Toast.makeText(activity, "${mode.label}: gilt ab der nächsten Routenberechnung", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
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

    /**
     * Everything greenWave has stored (tracks, stops, light events, known lights,
     * rides) as one ZIP in the app's folder on shared storage
     * (Android/data/<app>/files/export), then offered via the share sheet.
     * The raw material for checking how well the lights are learned.
     */
    private fun exportData(activity: Activity) {
        val app = activity.applicationContext
        Thread {
            val result = runCatching {
                val dir = File(app.getExternalFilesDir(null), "export").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date())
                val zip = File(dir, "greenwave-export-$stamp.zip")
                zip.outputStream().use { DataExport.writeZip(app, it) }
                zip
            }
            activity.runOnUiThread {
                result.onSuccess { zip ->
                    Toast.makeText(activity, "Exportiert: ${zip.name}", Toast.LENGTH_LONG).show()
                    val uri = FileProvider.getUriForFile(activity, BuildConfig.FILE_PROVIDER_AUTHORITY, zip)
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(intent, "greenWave-Daten teilen"))
                }.onFailure {
                    Toast.makeText(activity, "Export fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
                .apply { if (rides.size >= 2) setNeutralButton("Wegvergleich") { _, _ -> showWayComparison(activity) } }
                .show()
        }
    }

    /** Per pair of places ridden more than once: the ways taken there, fastest first. */
    private fun showWayComparison(activity: Activity) {
        GreenWaveController.wayComparison { connections ->
            if (activity.isFinishing || activity.isDestroyed) return@wayComparison
            val text = if (connections.isEmpty()) {
                "Noch keine Strecke mehrmals gefahren (Start und Ziel jeweils innerhalb von 300 m). " +
                    "Sobald du dieselbe Strecke öfter fährst, steht hier, welcher Weg im Schnitt schneller ist."
            } else {
                connections.mapIndexed { i, c ->
                    val km = "%.1f".format(Locale.GERMAN, c.ways.first().meanLengthMeters / 1000)
                    val header = "Strecke ${i + 1}: ${c.rideCount} Fahrten, seit ${RideHistory.day(c.ways.flatMap { it.rides }.minOf { it.startEpochSec })}, ca. $km km"
                    val fastest = c.ways.first().meanDurationSec
                    val lines = c.ways.mapIndexed { w, way ->
                        val diff = way.meanDurationSec - fastest
                        val km2 = "%.1f".format(Locale.GERMAN, way.meanLengthMeters / 1000)
                        "  Weg ${w + 1}: Ø ${RideHistory.minutes(way.meanDurationSec)} (${way.rides.size}×, $km2 km)" +
                            (if (w > 0) " · ${RideHistory.minutes(diff)} langsamer" else if (c.ways.size > 1) " · am schnellsten" else "") +
                            (if (way.suggestedByNavigation) " · Navi-Vorschlag" else "")
                    }
                    (listOf(header) + lines).joinToString("\n")
                }.joinToString("\n\n") + "\n\nDauer von Tür zu Tür, Stopps eingerechnet."
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Wegvergleich")
                .setMessage(text)
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

    /**
     * Hands the track as GPX to CoMaps' own import, which draws it and keeps it under "Lesezeichen und Tracks".
     * A ride imported before is shown again instead of being imported a second time.
     */
    private fun showOnMap(activity: Activity, r: RideSummary) {
        val name = RideHistory.trackName(r)
        val existing = BookmarkManager.INSTANCE.categories.firstOrNull { it.name == name }
        if (existing != null) {
            BookmarkManager.INSTANCE.setVisibility(existing.id, true)
            BookmarkManager.INSTANCE.showBookmarkCategoryOnMap(existing.id)
            return
        }
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

    /** State of sharing with other riders, with "Jetzt senden". */
    private fun showSharing(activity: Activity) {
        val settings = GreenWaveController.settings() ?: return
        val app = activity.applicationContext
        Thread {
            val queued = ShareQueue.size(app)
            val format = java.text.SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN)
            fun at(epochSec: Long) = if (epochSec == 0L) "–" else format.format(java.util.Date(epochSec * 1000))
            val message = when {
                !settings.shareEnabled -> "Aus. Einschalten unter Einstellungen → „Gemeinsam lernen (anonym)“."
                settings.shareServerUrl.isBlank() -> "An, aber kein Server eingetragen (Einstellungen). Bis dahin wird nichts gesendet."
                else -> "Wartend: $queued Ereignisse\nNächster Versand: ${at(settings.shareNextAt)}\n" +
                    "Zuletzt gesendet: ${at(settings.shareLastSent)}\n" +
                    "Nächster Abruf: ${at(settings.shareFetchAt)}\nZuletzt abgerufen: ${at(settings.shareLastFetch)}\n" +
                    "Nur im WLAN, 8–21 Uhr. Server: ${settings.shareServerUrl}"
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle("Gemeinsam lernen")
                    .setMessage(message)
                    .setNegativeButton("Schließen", null)
                if (settings.shareEnabled && settings.shareServerUrl.isNotBlank()) {
                    dialog.setPositiveButton("Jetzt senden") { _, _ ->
                        ShareWorker.sendNow(app)
                        Toast.makeText(activity, "Wird gesendet, sobald Netz da ist.", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show()
            }
        }.start()
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

        val share = SwitchCompat(context).apply {
            text = "Gemeinsam lernen (anonym)"
            isChecked = settings.shareEnabled
        }
        content.addView(share)
        content.addView(text(context,
            "Teilt einmal pro Woche, an einem zufälligen Tag und nur im WLAN, wann du an welcher Ampel gehalten " +
                "hast oder bei Grün durchgefahren bist. Ohne Konto, ohne Spur; Ampeln im Umkreis von 400–600 m " +
                "um Start und Ziel jeder Fahrt bleiben auf dem Gerät. Dafür lernt die App auch aus den Fahrten " +
                "anderer.", small = true))
        val shareUrl = EditText(context).apply {
            hint = "Server (https://…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.shareServerUrl)
            setSingleLine()
        }
        content.addView(shareUrl)

        val autoExport = SwitchCompat(context).apply {
            text = "Nach jeder Fahrt exportieren (Entwicklung)"
            isChecked = settings.autoExport
        }
        content.addView(autoExport)
        content.addView(text(context,
            "Legt alle greenWave-Daten nach jeder Fahrt als Documents/greenWave/greenwave-latest.zip ab, " +
                "z. B. für den Abgleich per Syncthing. Die Datei wird jedes Mal ersetzt.", small = true))

        val nerd = SwitchCompat(context).apply {
            text = "Nerd-Modus: Ampeln kalibrieren"
            isChecked = settings.nerdMode
        }
        content.addView(nerd)
        content.addView(text(context,
            "Neuer Menüpunkt „Ampel kalibrieren“: An der Ampel stehen und bei jedem Wechsel auf Grün oder Rot " +
                "tippen. Die Messung bleibt auf dem Gerät und fließt ins Lernen ein, auch wenn der Modus später " +
                "wieder aus ist (löschen im Menü bei eingeschaltetem Modus).", small = true))

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
                settings.autoExport = autoExport.isChecked
                settings.nerdMode = nerd.isChecked
                settings.shareEnabled = share.isChecked
                // Nothing queued may leave the device once sharing is off or the rider runs red.
                if (!share.isChecked || red.isChecked) Thread { ShareQueue.clear(context.applicationContext) }.start()
                // Off means a new device token next time, so turning it off and on breaks the link to earlier uploads.
                if (!share.isChecked) settings.shareDevice = ""
                val url = shareUrl.text.toString().trim()
                if (url.isEmpty() || url.startsWith("https://")) {
                    // A token belongs to one board.
                    if (url != settings.shareServerUrl) settings.shareDevice = ""
                    settings.shareServerUrl = url
                }
                else Toast.makeText(context, "Server-Adresse muss mit https:// beginnen, nicht gespeichert.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    internal fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 20f)
        setPadding(pad, dp(context, 8f), pad, 0)
    }

    internal fun text(context: Context, value: String, small: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 14f)
        setPadding(0, dp(context, 6f), 0, dp(context, 6f))
    }

    private fun number(context: Context, value: Double) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.roundToInt().toString())
        gravity = Gravity.CENTER
    }

    internal fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).roundToInt()
}
