package io.github.slrmoon.clack

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import clack.core.layout.AltLayer
import clack.core.layout.LayoutEditing

/** Edit the SYM panel pages: tap a key to change what it types on that page. Changes are saved at once. */
class SymEditorActivity : Activity() {
    private lateinit var store: LayoutStore
    private lateinit var prefs: Prefs
    private var page = 1
    private lateinit var tabs: LinearLayout
    private lateinit var gridHolder: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        store = LayoutStore(this)
        prefs = Prefs(this)
        title = "SYM-панель"
        page = if (prefs.symEmojiFirst) 2 else 1

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabs)
        root.addView(TextView(this).apply {
            text = "Нажмите на клавишу, чтобы задать, что она вводит на этой странице. Можно любой текст или эмодзи."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f; setPadding(0, dp(8), 0, dp(4))
        })
        gridHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(gridHolder)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("JSON…") { editJson() }, weight())
        actions.addView(button("Импорт…") { importJson() }, weight())
        actions.addView(button("Сбросить") { reset() }, weight())
        root.addView(actions)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        tabs.removeAllViews()
        for (p in if (prefs.symEmojiFirst) listOf(2, 1) else listOf(1, 2)) {
            tabs.addView(TextView(this).apply {
                text = LayoutStore.SYM_PAGE_NAMES[p - 1]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = if (p == page) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                alpha = if (p == page) 1f else 0.55f
                setPadding(0, dp(6), dp(20), dp(6))
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { page = p; render() }
            })
        }
        val map = store.symPage(page)
        gridHolder.removeAllViews()
        gridHolder.addView(KeyGrid.build(this, keyHeightDp = 54, mainSp = 20f,
            cell = { key -> KeyGrid.Cell(map[key] ?: "·", AppLauncher.keyLabel(key)) },
            onClick = { key -> editKey(key, map) }))
        status.text = if (store.hasCustomSymPage(page)) "Страница изменена вами." else "Стандартная страница."
    }

    private fun editKey(key: String, map: AltLayer) {
        val field = EditText(this).apply {
            setText(map[key] ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        AlertDialog.Builder(this)
            .setTitle("SYM + ${AppLauncher.keyLabel(key)}")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, ViewGroup.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ ->
                val m = LinkedHashMap(map.mappings)
                val v = field.text.toString()
                if (v.isEmpty()) m.remove(key) else m[key] = v
                save(LayoutEditing.keyMapToJson(m, LayoutStore.SYM_PAGE_NAMES[page - 1]))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save(json: String) {
        runCatching { store.saveSymPage(page, json) }
            .onSuccess { touch(); render() }
            .onFailure { toast("Ошибка: ${it.message}") }
    }

    private fun editJson() {
        val field = EditText(this).apply {
            setText(store.symPageJson(page))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.TOP
        }
        val dialog = AlertDialog.Builder(this).setTitle("JSON страницы").setView(field)
            .setPositiveButton("Применить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { store.saveSymPage(page, field.text.toString()) }
                    .onSuccess { touch(); render(); dialog.dismiss() }
                    .onFailure { toast("Ошибка в JSON: ${it.message}") }
            }
        }
        dialog.show()
    }

    private fun importJson() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
    }

    @Deprecated("Activity API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        runCatching { store.importSymPage(page, uri) }
            .onSuccess { touch(); render(); toast("Страница загружена") }
            .onFailure { toast("Не похоже на SYM-страницу: ${it.message}") }
    }

    private fun reset() {
        AlertDialog.Builder(this).setMessage("Вернуть стандартную страницу «${LayoutStore.SYM_PAGE_NAMES[page - 1]}»?")
            .setPositiveButton("Вернуть") { _, _ -> store.resetSymPage(page); touch(); render() }
            .setNegativeButton("Отмена", null).show()
    }

    /** The running keyboard reloads its pages when preferences change. */
    private fun touch() = prefs.sp.edit().putLong("sym_touched", System.currentTimeMillis()).apply()

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object { private const val REQ_IMPORT = 7 }
}
