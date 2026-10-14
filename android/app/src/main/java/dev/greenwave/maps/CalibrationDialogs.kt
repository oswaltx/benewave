package dev.greenwave.maps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import app.organicmaps.BuildConfig
import app.organicmaps.MwmApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.greenwave.shared.calibration.Calibration
import dev.greenwave.shared.geo.LatLng
import dev.greenwave.shared.geo.bearingDegrees
import dev.greenwave.shared.geo.distanceMeters
import dev.greenwave.shared.guidance.RouteAdvice
import dev.greenwave.shared.guidance.SignalWait
import dev.greenwave.shared.model.TrafficLight
import dev.greenwave.shared.tracking.LightEvent
import dev.greenwave.shared.tracking.RhythmEstimate
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Nerd-Modus (David 14.10.2026): calibrate one traffic light by hand. Standing at
 * the light, tap at every change to green and to red; the screen shows the phases,
 * the cycle and how the measurement compares with what was learned so far. Nothing
 * leaves the device (see [CalibrationStore]).
 */
internal object CalibrationDialogs {
    /** A GPS bearing this old or at walking pace is not trusted as the riding direction. */
    private const val GPS_BEARING_MAX_AGE_MS = 60_000L
    private const val GPS_BEARING_MIN_MPS = 1.5f

    fun showMenu(activity: Activity) {
        val items = listOf<Pair<String, () -> Unit>>(
            "Neue Messung" to { startNew(activity) },
            "Messungen exportieren (CSV)" to { export(activity) },
            "Alle Messungen löschen" to { confirmClear(activity) },
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("Ampel kalibrieren")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun startNew(activity: Activity) {
        val location = MwmApplication.from(activity).locationHelper.savedLocation
        if (location == null) {
            Toast.makeText(activity, "Kein Standort. GPS einschalten und kurz warten.", Toast.LENGTH_LONG).show()
            return
        }
        val here = LatLng(location.latitude, location.longitude)
        Toast.makeText(activity, "Suche Ampeln in der Nähe …", Toast.LENGTH_SHORT).show()
        GreenWaveController.lightsNear(here) { lights ->
            if (activity.isFinishing || activity.isDestroyed) return@lightsNear
            when (lights.size) {
                0 -> MaterialAlertDialogBuilder(activity)
                    .setTitle("Keine Ampel gefunden")
                    .setMessage("Im Umkreis von 80 m ist keine Ampel in der Karte eingetragen.")
                    .setPositiveButton("OK", null)
                    .show()
                1 -> setup(activity, lights.first(), location)
                else -> {
                    val rows = lights.mapIndexed { i, light ->
                        "Ampel ${i + 1} · ${distanceMeters(here, light).roundToInt()} m ${compassName(bearingDegrees(here, light))}"
                    }
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Welche Ampel?")
                        .setItems(rows.toTypedArray()) { _, which -> setup(activity, lights[which], location) }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
        }
    }

    /** Direction of approach and movement, then the measurement. */
    private fun setup(activity: Activity, light: LatLng, location: Location) {
        val content = GreenWaveDialogs.column(activity)
        val here = LatLng(location.latitude, location.longitude)
        content.addView(GreenWaveDialogs.text(activity,
            "Ampel ${distanceMeters(here, light).roundToInt()} m ${compassName(bearingDegrees(here, light))} von dir. " +
                "Gemessen wird das Signal für deine Fahrtrichtung."))

        val gpsBearing = location.takeIf {
            it.hasBearing() && it.hasSpeed() && it.speed >= GPS_BEARING_MIN_MPS &&
                System.currentTimeMillis() - it.time <= GPS_BEARING_MAX_AGE_MS
        }?.bearing?.roundToInt()?.mod(360)

        content.addView(GreenWaveDialogs.text(activity, "Fahrtrichtung"))
        val direction = RadioGroup(activity)
        val gps = RadioButton(activity).apply {
            id = 1
            text = "Aus der Fahrt: ${gpsBearing?.let { "${compassName(it.toDouble())} ($it°)" }}"
        }
        val compassButton = RadioButton(activity).apply { id = 2 }
        val unknown = RadioButton(activity).apply {
            id = 3
            text = "Unbekannt (gilt für alle Zufahrten)"
        }
        if (gpsBearing != null) direction.addView(gps)
        direction.addView(compassButton)
        direction.addView(unknown)
        content.addView(direction)

        val compass = Compass(activity) { deg ->
            compassButton.text = "Kompass: ${compassName(deg.toDouble())} ($deg°) – Handy in Fahrtrichtung halten"
        }
        compassButton.text = if (compass.available) "Kompass: Handy in Fahrtrichtung halten …" else "Kompass (nicht vorhanden)"
        compassButton.isEnabled = compass.available
        direction.check(
            when {
                gpsBearing != null -> gps.id
                compass.available -> compassButton.id
                else -> unknown.id
            },
        )

        content.addView(GreenWaveDialogs.text(activity, "Weiter fährst du"))
        val turns = listOf(
            "Geradeaus" to LightEvent.Turn.STRAIGHT,
            "Links" to LightEvent.Turn.LEFT,
            "Rechts" to LightEvent.Turn.RIGHT,
            "Egal (gilt für alle)" to null,
        )
        val turnGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        turns.forEachIndexed { i, (label, _) -> turnGroup.addView(RadioButton(activity).apply { id = 10 + i; text = label }) }
        turnGroup.check(10)
        content.addView(turnGroup)

        compass.start()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Ampel kalibrieren")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Messung starten") { _, _ ->
                val heading = when (direction.checkedRadioButtonId) {
                    gps.id -> gpsBearing
                    compassButton.id -> compass.headingDeg
                    else -> null
                }
                val turn = turns[turnGroup.checkedRadioButtonId - 10].second
                if (direction.checkedRadioButtonId == compassButton.id && heading == null) {
                    Toast.makeText(activity, "Kompass ohne Wert, Richtung bleibt unbekannt.", Toast.LENGTH_LONG).show()
                }
                measure(activity, light, heading, turn)
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { compass.stop() }
            .show()
    }

    private fun measure(activity: Activity, light: LatLng, headingDeg: Int?, turn: LightEvent.Turn?) {
        val startMono = SystemClock.elapsedRealtime()
        val startEpochMs = System.currentTimeMillis()
        val taps = mutableListOf<Calibration.Tap>()
        fun session() = Calibration.Session(light, headingDeg, turn, startEpochMs, taps.toList())

        val content = GreenWaveDialogs.column(activity)
        val movement = listOfNotNull(
            headingDeg?.let { "Fahrtrichtung ${compassName(it.toDouble())}" } ?: "Fahrtrichtung unbekannt",
            when (turn) {
                LightEvent.Turn.STRAIGHT -> "geradeaus"
                LightEvent.Turn.LEFT -> "links"
                LightEvent.Turn.RIGHT -> "rechts"
                LightEvent.Turn.U_TURN -> "wenden"
                null -> null
            },
        ).joinToString(", ")
        content.addView(GreenWaveDialogs.text(activity, "Bei jedem Wechsel tippen. $movement", small = true))
        val status = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, GreenWaveDialogs.dp(activity, 8f), 0, GreenWaveDialogs.dp(activity, 8f))
        }
        content.addView(status)

        val result = GreenWaveDialogs.text(activity, "")
        val model = GreenWaveDialogs.text(activity, "", small = true)
        var modelRequest = 0

        fun refresh() {
            val now = SystemClock.elapsedRealtime() - startMono
            val last = taps.lastOrNull()
            status.text = if (last == null) "Warte auf den ersten Wechsel" else {
                val color = if (last.change == Calibration.Change.GREEN) "Grün" else "Rot"
                "$color seit ${(now - last.atMs) / 1000} s"
            }
        }

        fun update() {
            refresh()
            result.text = describe(taps)
            val request = ++modelRequest
            if (Calibration.phases(taps).none { it.plausible }) {
                model.text = ""
                return
            }
            val summary = Calibration.summary(taps)
            GreenWaveController.calibrationModel(session()) {
                if (request == modelRequest) model.text = compare(summary, it)
            }
        }

        fun button(label: String, color: Int, change: Calibration.Change) = Button(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setBackgroundColor(color)
            setOnClickListener {
                val at = SystemClock.elapsedRealtime() - startMono
                val reason = Calibration.reject(taps, change, at)
                if (reason != null) {
                    Toast.makeText(activity, reason, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                taps.add(Calibration.Tap(at, change))
                update()
            }
        }

        val height = GreenWaveDialogs.dp(activity, 150f)
        val gap = GreenWaveDialogs.dp(activity, 6f)
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Wird\ngrün", Color.rgb(46, 125, 50), Calibration.Change.GREEN),
                LinearLayout.LayoutParams(0, height, 1f).apply { marginEnd = gap })
            addView(button("Wird\nrot", Color.rgb(198, 40, 40), Calibration.Change.RED),
                LinearLayout.LayoutParams(0, height, 1f).apply { marginStart = gap })
        })
        content.addView(Button(activity).apply {
            text = "Letzten Tipp zurücknehmen"
            setOnClickListener {
                if (taps.isNotEmpty()) {
                    taps.removeAt(taps.lastIndex)
                    update()
                }
            }
        })
        content.addView(result)
        content.addView(model)
        update()

        val ticker = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                refresh()
                ticker.postDelayed(this, 500)
            }
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Ampel kalibrieren")
            .setView(ScrollView(activity).apply { addView(content) })
            .setCancelable(false)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Verwerfen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val session = session()
                if (Calibration.phases(session.taps).none { it.plausible }) {
                    Toast.makeText(activity, "Noch keine vollständige Phase gemessen.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                GreenWaveController.saveCalibration(session)
                Toast.makeText(activity, "Messung gespeichert (${taps.size} Wechsel).", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (taps.size < 2) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                MaterialAlertDialogBuilder(activity)
                    .setMessage("Messung mit ${taps.size} Wechseln verwerfen?")
                    .setPositiveButton("Verwerfen") { _, _ -> dialog.dismiss() }
                    .setNegativeButton("Weiter messen", null)
                    .show()
            }
        }
        dialog.setOnDismissListener { ticker.removeCallbacks(tick) }
        dialog.show()
        // Measuring takes minutes; the screen must not go dark in between.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ticker.post(tick)
    }

    /** The latest phases and, after the first complete cycle, the summary. */
    private fun describe(taps: List<Calibration.Tap>): String {
        if (taps.size < 2) return ""
        val lines = mutableListOf<String>()
        val s = Calibration.summary(taps)
        val cycle = s.cycleSec
        if (cycle != null) {
            lines += "Umlauf ${sec(cycle)} · grün ${s.greenSec?.let { sec(it) } ?: "–"} · rot ${s.redSec?.let { sec(it) } ?: "–"}" +
                " (${s.cycles} ${if (s.cycles == 1) "Umlauf" else "Umläufe"})"
            if (s.cycles >= Calibration.MIN_CYCLES) {
                lines += if (s.steady) "Gleichmäßig – sieht nach festem Programm aus."
                else "Schwankt (Umlauf ±${sec(s.cycleSpreadSec / 2)}, grün ±${sec(s.greenSpreadSec / 2)}) – " +
                    "vermutlich verkehrsabhängig, gelernte Zeiten sind hier unsicher."
            }
        }
        if (s.skipped > 0) lines += "${s.skipped}× Wechsel ausgelassen oder Phase über ${Calibration.MAX_PHASE_SEC.roundToInt()} s – zählt nicht."
        lines += ""
        lines += Calibration.phases(taps).takeLast(6).reversed().map { p ->
            "${if (p.green) "grün" else "rot"} ${"%.1f".format(Locale.GERMAN, p.sec)} s" + if (p.plausible) "" else " (zählt nicht)"
        }
        return lines.joinToString("\n")
    }

    /** Measured wait against the one the arrival time uses so far, and what is learned without and with this measurement. */
    private fun compare(s: Calibration.Summary, m: GreenWaveController.CalibrationModel): String {
        fun trusted(e: RhythmEstimate?) = e != null && e.confidence >= RouteAdvice.MIN_CONFIDENCE
        fun program(e: RhythmEstimate) =
            "Umlauf ${e.cycleLengthSec} s, grün ${e.greenDurationSec} s (sicher ${(e.confidence * 100).roundToInt()} %)"
        fun wait(e: RhythmEstimate) =
            SignalWait.expectedWaitSec(TrafficLight(LatLng(0.0, 0.0), e.cycleLengthSec, e.greenStartOffsetSec, e.greenDurationSec))
        val lines = mutableListOf<String>()
        s.expectedWaitSec?.let { measured ->
            val used = if (trusted(m.before)) "${wait(m.before!!).roundToInt()} s aus dem gelernten Programm"
            else "${SignalWait.DEFAULT_WAIT_SEC.roundToInt()} s Standard für unbekannte Ampeln"
            lines += "Mittlere Wartezeit laut Messung: ${measured.roundToInt()} s (Ankunftszeit rechnet bisher mit $used)."
        }
        lines += "Bisher gelernt: " + if (trusted(m.before)) program(m.before!!) else "nichts"
        lines += when {
            m.contradicted -> "Die Messung passt nicht zu deinen bisherigen Stopps und Durchfahrten hier und wird nicht verwendet. " +
                "Richtige Ampel und Richtung gewählt?"
            m.measured == null -> "Für die Tempoempfehlung zählt die Messung ab ${Calibration.MIN_PROGRAM_CYCLES} gleichmäßigen Umläufen."
            else -> "Mit dieser Messung: " + if (trusted(m.after)) program(m.after!!) else "nichts"
        }
        lines += "Eine Messung gilt zur gleichen Tageszeit (±90 min), 4 Wochen lang."
        return lines.joinToString("\n")
    }

    private fun export(activity: Activity) {
        val app = activity.applicationContext
        GreenWaveController.calibrationExport { count, csv ->
            if (count == 0) {
                Toast.makeText(activity, "Noch keine Messungen.", Toast.LENGTH_SHORT).show()
                return@calibrationExport
            }
            Thread {
                val file = runCatching {
                    val dir = File(app.getExternalFilesDir(null), "export").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date())
                    File(dir, "greenwave-kalibrierung-$stamp.csv").apply { writeText(csv) }
                }
                activity.runOnUiThread {
                    file.onSuccess {
                        val uri = FileProvider.getUriForFile(activity, BuildConfig.FILE_PROVIDER_AUTHORITY, it)
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType("text/csv")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        activity.startActivity(Intent.createChooser(intent, "$count Messungen teilen"))
                    }.onFailure {
                        Toast.makeText(activity, "Export fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun confirmClear(activity: Activity) {
        GreenWaveController.calibrationExport { count, _ ->
            if (count == 0) {
                Toast.makeText(activity, "Noch keine Messungen.", Toast.LENGTH_SHORT).show()
                return@calibrationExport
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Alle $count Messungen löschen?")
                .setMessage("Was die App aus ihnen gelernt hat, geht damit verloren. Vorher exportieren?")
                .setPositiveButton("Löschen") { _, _ ->
                    GreenWaveController.clearCalibrations {
                        Toast.makeText(activity, "Messungen gelöscht.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun sec(value: Double) = "${value.roundToInt()} s"

    private fun compassName(deg: Double): String =
        listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    /**
     * Heading of the phone from the rotation vector: the top edge when it lies flat,
     * the back (camera side) when it stands upright, e.g. in a handlebar mount.
     * Magnetic north; the few degrees to true north don't matter for an approach.
     */
    private class Compass(context: Context, private val onChange: (Int) -> Unit) : SensorEventListener {
        private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        private val matrix = FloatArray(9)
        val available: Boolean get() = sensor != null
        var headingDeg: Int? = null
            private set

        fun start() {
            sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }

        fun stop() = manager.unregisterListener(this)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            // Rows are east, north, up; columns the phone's x, y and z axis.
            val flat = abs(matrix[8]) > 0.7f
            val east = if (flat) matrix[1] else -matrix[2]
            val north = if (flat) matrix[4] else -matrix[5]
            if (hypot(east, north) < 0.3f) return
            val deg = Math.toDegrees(atan2(east, north).toDouble()).roundToInt().mod(360)
            if (deg != headingDeg) {
                headingDeg = deg
                onChange(deg)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}
