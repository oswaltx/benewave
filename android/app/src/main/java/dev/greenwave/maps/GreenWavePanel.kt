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
import dev.greenwave.shared.guidance.SpeedAdvice
import kotlin.math.roundToInt

/**
 * Small card in the navigation screen: recommended speed for the green wave,
 * direction hint and distance to the next traffic light. Hidden unless a
 * bicycle route is active.
 */
class GreenWavePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val speed = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
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
        addView(speed)
        addView(detail)
        visibility = View.GONE
    }

    fun update(state: GreenWaveController.State) {
        if (state.status == GreenWaveController.LightsStatus.NO_ROUTE) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val guidance = state.guidance
        when {
            state.status == GreenWaveController.LightsStatus.LOADING -> show(GREY, "…", "Ampeln laden")
            state.status == GreenWaveController.LightsStatus.FAILED -> show(GREY, "–", "Ampeln n. verfügbar")
            guidance == null -> show(GREY, "–", "${state.lightCount} Ampeln")
            guidance.targetSpeedKmh == null -> show(GREY, "–", "keine Ampel voraus")
            else -> {
                val target = "${guidance.targetSpeedKmh!!.roundToInt()} km/h"
                val distance = guidance.nextLightDistanceMeters?.let { " · ${formatDistance(it)}" } ?: ""
                when (guidance.advice) {
                    SpeedAdvice.HOLD -> show(GREEN, target, "halten$distance")
                    SpeedAdvice.FASTER -> show(AMBER, target, "▲ schneller$distance")
                    SpeedAdvice.SLOWER -> show(AMBER, target, "▼ langsamer$distance")
                    SpeedAdvice.NONE -> show(GREY, target, distance.removePrefix(" · "))
                }
            }
        }
    }

    private fun show(color: Int, big: String, small: String) {
        background.setColor(color)
        speed.text = big
        detail.text = small
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
