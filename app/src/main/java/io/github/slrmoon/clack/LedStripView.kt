package io.github.slrmoon.clack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Minimal bar: a thin strip of coloured zones over the Titan 2 modifier keys, Pastiera-style:
 *   Shift | SYM | language (middle) | Ctrl | Alt
 * A zone shows the off colour, the "active" colour (one-shot / held) or the "locked" colour.
 * Nothing to tap: it only shows colour.
 */
class LedStripView(context: Context) : View(context) {
    enum class State { OFF, ACTIVE, LOCKED }

    /** Zone positions as fractions of the width, taken from Pastiera's Titan 2 layout. */
    private data class Zone(val x: Float, val w: Float)
    private val shiftZone = Zone(0.000f, 0.164f)
    private val symZone = Zone(0.168f, 0.164f)
    private val langZone = Zone(0.336f, 0.332f)
    private val ctrlZone = Zone(0.672f, 0.164f)
    private val altZone = Zone(0.840f, 0.160f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var heightDp: Int = 12
        set(v) { field = v; requestLayout() }

    private var colors = IntArray(5)

    fun update(prefs: Prefs, shift: State, sym: State, ctrl: State, alt: State, languageColor: Int) {
        fun c(led: Prefs.Led, s: State) = when (s) {
            State.OFF -> prefs.ledOffColor
            State.ACTIVE -> prefs.ledColor(led, locked = false)
            State.LOCKED -> prefs.ledColor(led, locked = true)
        }
        val next = intArrayOf(
            c(Prefs.Led.SHIFT, shift), c(Prefs.Led.SYM, sym), languageColor, c(Prefs.Led.CTRL, ctrl), c(Prefs.Led.ALT, alt)
        )
        if (!next.contentEquals(colors)) { colors = next; invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = resources.displayMetrics.density // 1dp between zones
        val radius = minOf(h / 3f, 4f * resources.displayMetrics.density)
        val zones = listOf(shiftZone, symZone, langZone, ctrlZone, altZone)
        zones.forEachIndexed { i, z ->
            paint.color = colors[i]
            rect.set(z.x * w + gap / 2, 0f, (z.x + z.w) * w - gap / 2, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
