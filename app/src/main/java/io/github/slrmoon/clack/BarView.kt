package io.github.slrmoon.clack

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The thin strip shown above the physical keyboard:
 *   [RU] [⇧]  suggestion | suggestion | suggestion
 * Tap the language label to switch layout, long-press it for settings.
 */
@SuppressLint("ViewConstructor")
class BarView(
    context: Context,
    private val onPick: (index: Int) -> Unit,
    private val onLanguageTap: () -> Unit,
    private val onLanguageLongPress: () -> Unit
) : LinearLayout(context) {

    data class Item(val text: String, val emphasized: Boolean = false, val quoted: Boolean = false)

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg = if (dark) Color.rgb(0x1C, 0x1C, 0x1E) else Color.rgb(0xF2, 0xF2, 0xF5)
    private val fg = if (dark) Color.rgb(0xF2, 0xF2, 0xF2) else Color.rgb(0x1C, 0x1C, 0x1E)
    private val dim = if (dark) Color.rgb(0x8E, 0x8E, 0x93) else Color.rgb(0x6C, 0x6C, 0x70)
    private val accent = if (dark) Color.rgb(0x5E, 0x8B, 0xFF) else Color.rgb(0x2E, 0x5B, 0xFF)
    private val divider = if (dark) Color.rgb(0x3A, 0x3A, 0x3C) else Color.rgb(0xD1, 0xD1, 0xD6)

    private val language = TextView(context)
    private val modifiers = TextView(context)
    private val slots = List(3) { TextView(context) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bg)
        minimumHeight = dp(40)

        language.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), accent)
            }
            setOnClickListener { onLanguageTap() }
            setOnLongClickListener { onLanguageLongPress(); true }
        }
        addView(language, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply { marginStart = dp(8) })

        modifiers.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(30)
        }
        addView(modifiers, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        slots.forEachIndexed { i, tv ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(divider) }, LayoutParams(dp(1), dp(20)))
            tv.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(fg)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                isClickable = true
                setOnClickListener { if (text.isNotEmpty()) onPick(i) }
            }
            addView(tv, LayoutParams(0, dp(40), 1f))
        }
    }

    fun setLanguage(label: String) { language.text = label }

    fun setModifiers(text: String) { modifiers.text = text }

    fun setItems(items: List<Item>) {
        slots.forEachIndexed { i, tv ->
            val item = items.getOrNull(i)
            if (item == null) {
                tv.text = ""
                return@forEachIndexed
            }
            tv.text = if (item.quoted) "«${item.text}»" else item.text
            tv.typeface = if (item.emphasized) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setTextColor(if (item.quoted) dim else if (item.emphasized) accent else fg)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
