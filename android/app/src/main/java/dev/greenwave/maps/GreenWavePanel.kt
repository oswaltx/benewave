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
 * to make it on green (slower ▼ and faster ▲), "halten" when the current speed
 * already works, and a "?" when the light's cycle is not learned yet — then
 * there is deliberately no speed. Hidden unless a bicycle route is active.
 */
class GreenWavePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val slower = speedText()
    private val faster = speedText()
    private val speeds = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(slower)
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
                val low = o?.slowerKmh?.let { "▼${it.roundToInt()}" }
                val high = o?.fasterKmh?.let { "▲${it.roundToInt()}" }
                when {
                    o?.holdKmh != null ->
                        show(GREEN, "✓", null, "halten · ${formatDistance(distance)}$learned")
                    low == null && high == null ->
                        show(GREY, "–", null, "kein Grün erreichbar · ${formatDistance(distance)}$learned")
                    else -> show(AMBER, low ?: "–", high ?: "–", "km/h · ${formatDistance(distance)}$learned")
                }
            }
        }
    }

    /** [left]/[right]: the two speeds; with [right] null only [left] is shown, centred. */
    private fun show(color: Int, left: String, right: String?, small: String) {
        background.setColor(color)
        slower.text = left
        faster.text = right ?: ""
        faster.visibility = if (right == null) View.GONE else View.VISIBLE
        speeds.getChildAt(1).visibility = faster.visibility
        detail.text = small
    }

    private fun learnedSuffix(state: GreenWaveController.State): String =
        when {
            state.learnedCount == 0 -> ""
            state.liveCount > 0 -> "\n${state.learnedCount}/${state.lightCount} bekannt, ${state.liveCount} live"
            else -> "\n${state.learnedCount}/${state.lightCount} gelernt"
        }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "%.1f km".format(meters / 1000)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        val GREEN = Color.parseColor("#2E7D32")
        val AMBER = Color.parseColor("#E08E0B")
        val GREY = Color.parseColor("#B3424242")
    }
}
