package io.github.slrmoon.clack

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import clack.core.launch.AppEntry
import clack.core.launch.AppFilter

/** Assign apps to SYM + letter: tap a key, pick an app (or remove the assignment). */
class QuickLaunchEditorActivity : Activity() {
    private lateinit var launcher: AppLauncher
    private lateinit var gridHolder: LinearLayout
    private lateinit var list: LinearLayout
    private var apps: List<AppEntry> = emptyList()
    private val icons = HashMap<String, Drawable?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        launcher = AppLauncher(this)
        title = "Быстрый запуск"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        root.addView(text("Нажмите на клавишу и выберите приложение. Потом SYM + эта буква (вне текстовых полей) сразу его откроет. На буквы без назначения открывается список приложений на эту букву.", 13f, 0.7f))
        gridHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(gridHolder)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
        Thread {
            val all = launcher.apps().sortedBy { it.label.lowercase() }
            runOnUiThread { apps = all }
        }.start()
    }

    private fun render() {
        val assigned = launcher.assignments().toMap()
        gridHolder.removeAllViews()
        gridHolder.addView(KeyGrid.build(this, keyHeightDp = 56, mainSp = 11f,
            cell = { key -> KeyGrid.Cell(assigned[key]?.label?.take(7) ?: "·", AppLauncher.keyLabel(key)) },
            onClick = ::pickFor))
        list.removeAllViews()
        if (assigned.isEmpty()) list.addView(text("Пока ничего не назначено.", 14f, 0.7f))
        for ((key, app) in assigned.toSortedMap()) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            line.addView(ImageView(this).apply { setImageDrawable(icon(app)) }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            line.addView(text("SYM+${AppLauncher.keyLabel(key)}  →  ${app.label}", 15f, 1f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(android.widget.Button(this).apply { text = "✕"; setOnClickListener { launcher.unassign(key); render() } })
            list.addView(line)
        }
    }

    private fun pickFor(key: String) {
        if (apps.isEmpty()) apps = launcher.apps().sortedBy { it.label.lowercase() }
        var shown = apps
        val search = EditText(this).apply {
            hint = "Поиск приложения"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            isSingleLine = true
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert as? LinearLayout) ?: LinearLayout(this@QuickLaunchEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
                    addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(icon(shown[p]))
                (row.getChildAt(1) as TextView).text = shown[p].label
                return row
            }
        }
        val lv = ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        }
        // apps on this key's letters first, the rest after
        val letters = AppFilter.lettersForKey(key, LayoutStore(this).let { st -> Prefs(this).enabledLayouts.mapNotNull { st.load(it) } })
        val onLetter = AppFilter.startingWith(apps, letters)
        shown = onLetter + apps.filter { it !in onLetter }
        val dialog = AlertDialog.Builder(this)
            .setTitle("SYM + ${AppLauncher.keyLabel(key)}")
            .setView(box)
            .setNeutralButton("Убрать") { _, _ -> launcher.unassign(key); render() }
            .setNegativeButton("Отмена", null)
            .create()
        lv.setOnItemClickListener { _, _, p, _ ->
            launcher.assign(key, shown[p])
            dialog.dismiss()
            render()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString()
                shown = if (q.isBlank()) onLetter + apps.filter { it !in onLetter } else AppFilter.search(apps, q)
                adapter.notifyDataSetChanged()
            }
        })
        dialog.show()
    }

    private fun icon(app: AppEntry) = icons.getOrPut(app.id) { launcher.icon(app) }
    private fun text(s: String, sp: Float, a: Float) = TextView(this).apply { text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); alpha = a }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
