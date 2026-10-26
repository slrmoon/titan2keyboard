package io.github.slrmoon.clack

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import clack.core.layout.AltLayer

/**
 * Symbol / emoji panel opened with SYM. Each key of the grid shows what the physical key types
 * on this page; tap a cell or press the key. SYM again goes to the next page.
 */
@SuppressLint("ViewConstructor")
class SymPanelView(
    context: Context,
    private val onSymbol: (String) -> Unit,
    private val onPage: (Int) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val accent = if (dark) Color.rgb(0x5E, 0x8B, 0xFF) else Color.rgb(0x2E, 0x5B, 0xFF)
    private val dim = if (dark) Color.rgb(0x8E, 0x8E, 0x93) else Color.rgb(0x6C, 0x6C, 0x70)
    private val header = LinearLayout(context)
    private val gridHolder = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(if (dark) Color.rgb(0x1C, 0x1C, 0x1E) else Color.rgb(0xF2, 0xF2, 0xF5))
        setPadding(dp(4), dp(2), dp(4), dp(4))
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        addView(gridHolder)
    }

    fun show(page: Int, names: List<String>, map: AltLayer) {
        header.removeAllViews()
        names.forEachIndexed { i, name ->
            header.addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = if (i + 1 == page) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (i + 1 == page) accent else dim)
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER
                setOnClickListener { onPage(i + 1) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        header.addView(TextView(context).apply {
            text = "SYM — дальше · держать SYM+буква — приложение"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(dim)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(dim)
            setPadding(dp(14), 0, dp(10), 0)
            gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        gridHolder.removeAllViews()
        gridHolder.addView(KeyGrid.build(context, keyHeightDp = 46, mainSp = 20f,
            cell = { key -> KeyGrid.Cell(map[key] ?: "", AppLauncher.keyLabel(key)) },
            onClick = { key -> map[key]?.let(onSymbol) }))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
