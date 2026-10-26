package io.github.slrmoon.clack

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import clack.core.launch.AppEntry
import clack.core.launch.AppFilter

/**
 * Shown for SYM + a key that has no app assigned: the apps whose name starts with that key's
 * letter (in any enabled layout). Tap or Enter opens an app; long-press assigns it to the key.
 * Typing narrows the list.
 */
class AppPickerActivity : Activity() {
    private lateinit var launcher: AppLauncher
    private lateinit var keyName: String
    private var letters: Set<String> = emptySet()
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val adapter = Adapter()
    private val icons = HashMap<String, android.graphics.drawable.Drawable?>()
    private lateinit var title: TextView
    private lateinit var query: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = AppLauncher(this)
        keyName = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        letters = intent.getStringArrayExtra(EXTRA_LETTERS)?.toSet() ?: emptySet()

        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, dp(8)) }
        title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Нажмите — открыть. Долгое нажатие — назначить на SYM+${AppLauncher.keyLabel(keyName)}."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(8))
        })
        query = EditText(this).apply {
            hint = "Уточнить…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let(::open); true }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    shown.firstOrNull()?.let(::open); true
                } else false
            }
        }
        root.addView(query)
        val list = ListView(this).apply {
            adapter = this@AppPickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let(::open) }
            setOnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let(::assign); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        setContentView(root)

        Thread {
            val apps = launcher.apps()
            runOnUiThread { all = apps; refresh() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        finish() // behaves like a popup: gone as soon as something else is in front
    }

    private fun refresh() {
        val q = query.text.toString()
        val byLetter = AppFilter.startingWith(all, letters)
        shown = when {
            q.isNotBlank() -> AppFilter.search(all, q)
            byLetter.isNotEmpty() -> byLetter
            else -> AppFilter.search(all, "")
        }
        val lettersText = letters.joinToString(" / ") { it.uppercase() }
        title.text = when {
            q.isNotBlank() -> "Поиск: $q"
            byLetter.isEmpty() && all.isNotEmpty() -> "На $lettersText ничего нет — все приложения"
            else -> "Приложения на $lettersText"
        }
        adapter.notifyDataSetChanged()
    }

    private fun open(app: AppEntry) {
        if (!launcher.launch(app)) Toast.makeText(this, "Не удалось открыть ${app.label}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun assign(app: AppEntry) {
        launcher.assign(keyName, app)
        Toast.makeText(this, "SYM+${AppLauncher.keyLabel(keyName)} → ${app.label}", Toast.LENGTH_SHORT).show()
        open(app)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
                addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            }
            val app = shown[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(icons.getOrPut(app.id) { launcher.icon(app) })
            (row.getChildAt(1) as TextView).text = app.label
            return row
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LETTERS = "letters"

        fun intent(context: Context, keyName: String, letters: Set<String>): Intent =
            Intent(context, AppPickerActivity::class.java)
                .putExtra(EXTRA_KEY, keyName)
                .putExtra(EXTRA_LETTERS, letters.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
