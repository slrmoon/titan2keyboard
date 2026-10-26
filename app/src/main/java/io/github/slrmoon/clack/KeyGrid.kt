package io.github.slrmoon.clack

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import clack.core.layout.LayoutEditing

/**
 * The 26 Titan 2 letter keys laid out like on the phone (bottom row split by the space bar).
 * Used by the SYM panel and by both editors.
 */
object KeyGrid {
    data class Cell(val main: CharSequence, val hint: String? = null)

    fun build(
        context: Context,
        keyHeightDp: Int,
        mainSp: Float,
        cell: (keyName: String) -> Cell,
        onClick: (keyName: String) -> Unit
    ): LinearLayout {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val keyBg = if (dark) Color.rgb(0x2C, 0x2C, 0x2E) else Color.rgb(0xE5, 0xE5, 0xEA)
        val hintColor = if (dark) Color.rgb(0x8E, 0x8E, 0x93) else Color.rgb(0x6C, 0x6C, 0x70)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (row in LayoutEditing.TITAN2_ROWS) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            // narrower rows keep the same key size as the 10-key top row
            val missing = 10 - row.size - row.count { it == LayoutEditing.SPACE_GAP }
            if (missing > 0) line.addView(View(context), LinearLayout.LayoutParams(0, 1, missing / 2f))
            for (key in row) {
                if (key == LayoutEditing.SPACE_GAP) {
                    line.addView(TextView(context).apply { text = "␣"; gravity = Gravity.CENTER; alpha = 0.35f },
                        LinearLayout.LayoutParams(0, dp(keyHeightDp), 2f))
                    continue
                }
                val c = cell(key)
                val text = SpannableStringBuilder(c.main)
                if (!c.hint.isNullOrEmpty()) {
                    val start = text.length
                    text.append('\n').append(c.hint)
                    text.setSpan(RelativeSizeSpan(0.55f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(ForegroundColorSpan(hintColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.addView(TextView(context).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, mainSp)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(keyBg) }
                    setOnClickListener { onClick(key) }
                }, LinearLayout.LayoutParams(0, dp(keyHeightDp), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            if (missing > 0) line.addView(View(context), LinearLayout.LayoutParams(0, 1, missing / 2f))
            grid.addView(line)
        }
        return grid
    }
}
