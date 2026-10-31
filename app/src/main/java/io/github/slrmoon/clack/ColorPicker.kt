package io.github.slrmoon.clack

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast

/** Palette + HEX colour dialog, and a small swatch view for settings rows. */
object ColorPicker {
    private val PALETTE = longArrayOf(
        0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
        0xFF009688, 0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
        0xFF795548, 0xFF9E9E9E, 0xFF607D8B, 0xFFFFFFFF, 0xFF000000, 0xFF1B5E20, 0xFF0D47A1, 0xFFB71C1C,
        0x40808080, 0x00000000
    ).map { it.toInt() }.toIntArray()

    fun hex(c: Int) = String.format("#%08X", c)

    fun swatch(context: Context, color: Int, sizeDp: Int = 28): View = View(context).apply {
        val px = dp(context, sizeDp)
        layoutParams = LinearLayout.LayoutParams(px, px).apply { marginEnd = dp(context, 8) }
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 6).toFloat()
            setColor(color)
            setStroke(dp(context, 1), 0x60808080)
        }
    }

    fun show(context: Context, title: String, current: Int, onPick: (Int) -> Unit) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), 0)
        }
        val preview = swatch(context, current, 40)
        val field = EditText(context).apply {
            setText(hex(current))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "#AARRGGBB или #RRGGBB"
        }
        fun parse(s: String): Int? = runCatching {
            val t = s.trim().removePrefix("#")
            when (t.length) {
                6 -> (0xFF000000 or t.toLong(16)).toInt()
                8 -> t.toLong(16).toInt()
                else -> null
            }
        }.getOrNull()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                parse(s.toString())?.let { (preview.background as GradientDrawable).setColor(it) }
            }
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(preview)
        row.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        val grid = GridLayout(context).apply { columnCount = 7; setPadding(0, dp(context, 10), 0, 0) }
        for (c in PALETTE) {
            grid.addView(swatch(context, c, 34).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.let { layoutParams = GridLayout.LayoutParams().apply { width = it.width; height = it.height; setMargins(4, 4, 4, 4) } }
                setOnClickListener { field.setText(hex(c)) }
            })
        }
        box.addView(grid)
        AlertDialog.Builder(context).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val c = parse(field.text.toString())
                if (c == null) Toast.makeText(context, "Неверный цвет", Toast.LENGTH_SHORT).show() else onPick(c)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

}
