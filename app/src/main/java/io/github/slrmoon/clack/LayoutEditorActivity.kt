package io.github.slrmoon.clack

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import clack.core.layout.KeyMapping
import clack.core.layout.KeyboardLayout
import clack.core.layout.LayoutEditing

/**
 * On-device layout editor. Shows the Titan 2 letter keys as they sit on the phone; tapping a key
 * edits what it types. Letters separated by spaces become multi-tap ("з х ъ"). The raw Pastiera
 * JSON can be edited directly as well.
 */
class LayoutEditorActivity : Activity() {
    private lateinit var store: LayoutStore
    private lateinit var prefs: Prefs
    private var existingId: String? = null
    private var name = ""
    private var description = ""
    private val mappings = LinkedHashMap<String, KeyMapping>()
    private var dirty = false
    private var originalName = ""

    private lateinit var nameField: EditText
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        store = LayoutStore(this)
        prefs = Prefs(this)
        existingId = intent.getStringExtra(EXTRA_ID)
        val base = (existingId ?: intent.getStringExtra(EXTRA_BASE))?.let { store.load(it) }
        if (base != null) {
            name = if (existingId != null) base.name else "${base.name} (копия)"
            description = base.description
            mappings.putAll(base.mappings)
        } else {
            name = "Моя раскладка"
        }
        originalName = name
        title = if (existingId != null) "Редактор раскладки" else "Новая раскладка"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        root.addView(label("Название"))
        nameField = EditText(this).apply {
            setText(name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(nameField)
        root.addView(note("Нажмите на клавишу, чтобы задать её буквы. Несколько букв через пробел — переключаются повторным нажатием (мульти-тап)."))
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(grid)
        root.addView(note("Alt-слой (цифры и символы) общий для всех раскладок и настраивается отдельно."))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Сохранить") { save() }, weight())
        actions.addView(button("JSON…") { editJson() }, weight())
        actions.addView(button("Поделиться") { share() }, weight())
        root.addView(actions)

        setContentView(ScrollView(this).apply { addView(root) })
        renderGrid()
    }

    private fun renderGrid() {
        grid.removeAllViews()
        grid.addView(KeyGrid.build(this, keyHeightDp = 56, mainSp = 18f,
            cell = { key ->
                val m = mappings[key]
                if (m == null) KeyGrid.Cell("·")
                else KeyGrid.Cell(m.primary.lower, m.outputs.drop(1).joinToString(" ") { it.lower }.ifEmpty { null })
            },
            onClick = ::editKey))
    }

    private fun editKey(key: String) {
        val field = EditText(this).apply {
            setText(LayoutEditing.formatKey(mappings[key]))
            // no suggestions or auto-correction while typing raw letters
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
            addView(note("Например: «д», или «д ж э» для мульти-тапа. Заглавную можно указать через /: «ß/ẞ»."))
        }
        AlertDialog.Builder(this)
            .setTitle("Клавиша ${key.removePrefix("KEYCODE_")}")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val m = LayoutEditing.parseKey(field.text.toString())
                if (m == null) mappings.remove(key) else mappings[key] = m
                dirty = true
                renderGrid()
            }
            .setNeutralButton("Очистить") { _, _ -> mappings.remove(key); dirty = true; renderGrid() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun current(id: String): KeyboardLayout {
        name = nameField.text.toString().trim().ifEmpty { "Без названия" }
        return KeyboardLayout(id, name, description, LinkedHashMap(mappings))
    }

    private fun editJson() {
        val field = EditText(this).apply {
            setText(LayoutEditing.toJson(current(existingId ?: "draft")))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.TOP
            setHorizontallyScrolling(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("JSON раскладки")
            .setView(field)
            .setPositiveButton("Применить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { KeyboardLayout.parse(existingId ?: "draft", field.text.toString()) }
                    .onSuccess { l ->
                        name = l.name; description = l.description
                        mappings.clear(); mappings.putAll(l.mappings)
                        nameField.setText(name)
                        dirty = true
                        renderGrid()
                        dialog.dismiss()
                    }
                    .onFailure { toast("Ошибка в JSON: ${it.message}") }
            }
        }
        dialog.show()
    }

    private fun save() {
        if (mappings.isEmpty()) { toast("Задайте хотя бы одну клавишу"); return }
        val id = existingId ?: LayoutEditing.idFor(nameField.text.toString(), store.allIds())
        runCatching { store.save(current(id)) }
            .onSuccess {
                if (existingId == null) {
                    prefs.enabledLayouts = (prefs.enabledLayouts + id).distinct()
                    existingId = id
                }
                // the running keyboard reloads layouts when preferences change
                prefs.sp.edit().putLong("layouts_touched", System.currentTimeMillis()).apply()
                dirty = false
                toast("Сохранено")
                finish()
            }
            .onFailure { toast("Не удалось сохранить: ${it.message}") }
    }

    private fun share() {
        val json = LayoutEditing.toJson(current(existingId ?: "layout"))
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json)
                .putExtra(Intent.EXTRA_SUBJECT, "$name.json"),
            "Поделиться раскладкой"))
    }

    @Deprecated("Activity API without AndroidX")
    override fun onBackPressed() {
        if (nameField.text.toString() != originalName) dirty = true
        if (!dirty) { super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setMessage("Сохранить изменения?")
            .setPositiveButton("Сохранить") { _, _ -> save() }
            .setNegativeButton("Не сохранять") { _, _ -> finish() }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun label(s: String) = TextView(this).apply { text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f }
    private fun note(s: String) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f; setPadding(0, dp(6), 0, dp(4))
    }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_BASE = "base"

        fun edit(context: Context, id: String) = Intent(context, LayoutEditorActivity::class.java).putExtra(EXTRA_ID, id)
        /** New layout, optionally starting as a copy of [baseId]. */
        fun create(context: Context, baseId: String?) = Intent(context, LayoutEditorActivity::class.java).putExtra(EXTRA_BASE, baseId)
    }
}
