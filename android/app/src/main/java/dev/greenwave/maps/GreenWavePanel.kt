package dev.greenwave.maps

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Small card in the navigation screen for the next traffic light: the two ways
 * to make it on green (slower ▼ for a later green, faster ▲ for an earlier
 * one), with a ✓ between them when the current speed already works, and a "?"
 * when the light's cycle is not learned yet — then there is deliberately no
 * speed. An option outside the rider's speed range is dimmed. Hidden unless a
 * bicycle route is active.
 */
class GreenWavePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val slower = speedText()
    private val hold = speedText()
    private val faster = speedText()
    private val speeds = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(slower)
        addView(View(context), LayoutParams(dp(12f).roundToInt(), 1))
        addView(hold)
        addView(View(context), LayoutParams(dp(12f).roundToInt(), 1))
        addView(faster)
    }
    private val detail = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }
    private val background = GradientDrawable().apply { cornerRadius = dp(14f) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = dp(10f).roundToInt()
        setPadding(pad, dp(6f).roundToInt(), pad, dp(6f).roundToInt())
        minimumWidth = dp(96f).roundToInt()
        setBackground(background)
        elevation = dp(4f)
        addView(speeds)
        addView(detail)
        visibility = View.GONE
        // Long press opens greenWave's settings (speed range, rider profile, red-light flag).
        setOnLongClickListener {
            GreenWaveDialogs.showSettings(context)
            true
        }
    }

    private fun speedText() = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    fun update(state: GreenWaveController.State) {
        if (state.status == GreenWaveController.LightsStatus.NO_ROUTE) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val advice = state.advice
        val distance = advice?.nextLightDistanceMeters
        val learned = learnedSuffix(state)
        when {
            state.status == GreenWaveController.LightsStatus.LOADING -> show(GREY, "…", null, "Ampeln laden")
            state.status == GreenWaveController.LightsStatus.FAILED -> show(GREY, "–", null, "Ampeln n. verfügbar")
            advice == null -> show(GREY, "–", null, "${state.lightCount} Ampeln")
            distance == null -> show(GREY, "–", null, "keine Ampel voraus")
            !advice.nextLightKnown -> show(GREY, "?", null, "Ampel unbekannt · ${formatDistance(distance)}$learned")
            else -> {
                val o = advice.options
                if (o == null || (o.holdKmh == null && o.slowerKmh == null && o.fasterKmh == null)) {
                    show(GREY, "–", null, "kein Grün erreichbar · ${formatDistance(distance)}$learned")
                } else {
                    val holding = o.holdKmh != null
                    show(
                        if (holding) GREEN else AMBER,
                        "▼${o.slowerKmh?.roundToInt() ?: "–"}",
                        "▲${o.fasterKmh?.roundToInt() ?: "–"}",
                        (if (holding) "halten" else "km/h") + " · ${formatDistance(distance)}$learned",
                        middle = if (holding) "✓" else null,
                    )
                    slower.alpha = if (o.slowerOutOfRange || o.slowerKmh == null) DIMMED else 1f
                    faster.alpha = if (o.fasterOutOfRange || o.fasterKmh == null) DIMMED else 1f
                }
            }
        }
    }

    /**
     * [left]/[right]: the two speeds, [middle] the ✓ between them; with [right]
     * null only [left] is shown, centred.
     */
    private fun show(color: Int, left: String, right: String?, small: String, middle: String? = null) {
        background.setColor(color)
        slower.text = left
        slower.alpha = 1f
        faster.alpha = 1f
        faster.text = right ?: ""
        faster.visibility = if (right == null) View.GONE else View.VISIBLE
        hold.text = middle ?: ""
        hold.visibility = if (middle == null) View.GONE else View.VISIBLE
        speeds.getChildAt(1).visibility = if (right == null && middle == null) View.GONE else View.VISIBLE
        speeds.getChildAt(3).visibility = hold.visibility
        detail.text = small
    }

    private fun learnedSuffix(state: GreenWaveController.State): String {
        val learned = when {
            state.learnedCount == 0 -> null
            state.liveCount > 0 -> "${state.learnedCount}/${state.lightCount} bekannt, ${state.liveCount} live"
            else -> "${state.learnedCount}/${state.lightCount} gelernt"
        }
        // Part of the arrival time; unknown lights count a fixed estimate each.
        val wait = state.signalWait?.takeIf { it.lights > 0 }?.let { waitText(it.waitSec, it.lights - it.known) }
        val parts = listOfNotNull(learned, wait)
        return if (parts.isEmpty()) "" else "\n" + parts.joinToString(" · ")
    }

    /** Waits at the lights still ahead; [estimated] of them count a fixed guess (unknown program). */
    private fun waitText(sec: Double, estimated: Int): String {
        val time = when {
            sec < 10 -> "<10 s"
            sec < 90 -> "~${(sec / 10).roundToInt() * 10} s"
            else -> "~${(sec / 60).roundToInt()} min"
        }
        return "Warten $time" + if (estimated > 0) " ($estimated geschätzt)" else ""
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "%.1f km".format(meters / 1000)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        val GREEN = Color.parseColor("#2E7D32")
        val AMBER = Color.parseColor("#E08E0B")
        val GREY = Color.parseColor("#B3424242")
        /** Alpha of an option outside the rider's speed range, or of a missing one. */
        const val DIMMED = 0.45f
    }
}
