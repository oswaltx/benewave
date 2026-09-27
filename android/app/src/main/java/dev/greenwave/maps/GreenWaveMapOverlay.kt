package dev.greenwave.maps

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Card on the map screen while greenWave tracking runs: what has been recorded
 * so far, the last registered stop or green pass-through (the card flashes
 * green for a moment), and the legend of the traffic light dots. Tap opens the
 * greenWave menu (e.g. to stop tracking). Added in code on top of CoMaps'
 * layout, so CoMaps' own layouts stay untouched.
 */
internal class GreenWaveMapOverlay private constructor(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val background = GradientDrawable().apply { cornerRadius = dp(14f) }
    private val title = text(15f, bold = true)
    private val stats = text(13f)
    private val event = text(13f)
    private val legend = text(11f).apply { text = legendText() }
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(12f).roundToInt()
        setPadding(pad, dp(8f).roundToInt(), pad, dp(8f).roundToInt())
        background = this@GreenWaveMapOverlay.background
        elevation = dp(6f)
        addView(title)
        addView(stats)
        addView(event)
        addView(legend)
        visibility = View.GONE
        setOnClickListener { GreenWaveDialogs.showMenu(activity) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (activity.isDestroyed) return
            update()
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun attach() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(88f).roundToInt() })
        handler.post(tick)
    }

    private fun update() {
        if (!GreenWaveTrackingService.running) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        title.text = "● greenWave-Tracking"
        stats.text = GreenWaveTrackingService.summary.ifEmpty { "wartet auf GPS …" }
        val last = GreenWaveController.trackingLastEvent
        event.text = last ?: "Tippen: Menü / Tracking beenden"
        val sinceEvent = System.currentTimeMillis() - GreenWaveController.trackingLastEventAt
        background.setColor(if (last != null && sinceEvent < FLASH_MS) GREEN else DARK)
    }

    /** The dot legend with each dot in its map colour (see GreenWaveMarks.cpp). */
    private fun legendText(): CharSequence {
        val builder = SpannableStringBuilder()
        for ((color, label) in listOf(
            "#9E9E9E" to "keine Daten  ", "#E08E0B" to "lernt  ", "#2E7D32" to "sicher  ", "#1565C0" to "live",
        )) {
            val start = builder.length
            builder.append("● ")
            builder.setSpan(ForegroundColorSpan(Color.parseColor(color)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(label)
        }
        return builder
    }

    private fun text(sp: Float, bold: Boolean = false) = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics)

    companion object {
        private const val FLASH_MS = 4_000L
        private val GREEN = Color.parseColor("#E62E7D32")
        private val DARK = Color.parseColor("#E6212121")

        @JvmStatic
        fun attach(activity: Activity) {
            GreenWaveMapOverlay(activity).attach()
        }
    }
}
