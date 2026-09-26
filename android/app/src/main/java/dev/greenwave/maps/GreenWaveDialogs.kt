package dev.greenwave.maps

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.greenwave.shared.algorithm.SpeedRangeKmh
import dev.greenwave.shared.geo.LatLng
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
        MaterialAlertDialogBuilder(activity)
            .setTitle("greenWave")
            .setItems(arrayOf("Ampeldaten", "Einstellungen")) { _, which ->
                if (which == 0) showLightData(activity) else showSettings(activity)
            }
            .show()
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
                    s.stops > 0 -> "noch zu wenig Stopps"
                    else -> "noch keine Stopps"
                }
                "${if (s.trusted) "●" else "○"} $what · ${s.stops} Stopps\n$where"
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Ampeldaten: $learned von ${stats.size} gelernt")
                .apply {
                    if (rows.isEmpty()) setMessage("Noch keine Ampeln. Starte eine Navigation mit dem Fahrrad-Profil.")
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
