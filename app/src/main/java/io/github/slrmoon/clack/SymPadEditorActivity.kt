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
import clack.core.sympad.SymPadAction
import clack.core.sympad.SymPadMap

/** SymPad scheme editor: tap a key to choose what SYM + that key does. Saved immediately. */
class SymPadEditorActivity : Activity() {
    private lateinit var store: LayoutStore
    private lateinit var prefs: Prefs
    private lateinit var map: SymPadMap
    private lateinit var gridHolder: LinearLayout
    private lateinit var legend: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        store = LayoutStore(this)
        prefs = Prefs(this)
        map = store.symPadMap()
        title = "Схема SymPad"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24)) }
        root.addView(text("Удерживайте SYM и нажмите клавишу — выполнится её действие. Нажмите на клавишу здесь, чтобы выбрать действие. Не назначенные клавиши в режиме SymPad ничего не печатают.", 13f, 0.7f))
        gridHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(gridHolder)
        legend = text("", 13f, 0.85f)
        root.addView(legend)
        status = text("", 13f, 0.6f).apply { setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("JSON…") { editJson() }, weight())
        actions.addView(button("Поделиться") { share() }, weight())
        actions.addView(button("По умолчанию") { reset() }, weight())
        root.addView(actions)
        val more = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        more.addView(button("Очистить всё") { clearAll() }, weight())
        more.addView(button("Импорт файла…") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        }, weight())
        root.addView(more)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        gridHolder.removeAllViews()
        gridHolder.addView(KeyGrid.build(this, keyHeightDp = 54, mainSp = 18f,
            cell = { key -> KeyGrid.Cell(map[key].short.ifEmpty { "·" }, AppLauncher.keyLabel(key)) },
            onClick = ::chooseAction))
        legend.text = map.actions.entries
            .sortedBy { it.value.ordinal }
            .joinToString("\n") { (k, a) -> "SYM+${AppLauncher.keyLabel(k)}  ${a.short}  ${a.label}" }
            .ifEmpty { "Ничего не назначено." }
        status.text = if (store.hasCustomSymPadMap()) "Схема изменена вами." else "Стандартная схема."
    }

    private fun chooseAction(key: String) {
        val all = SymPadAction.values().toList()
        val labels = all.map { a ->
            when (a) {
                SymPadAction.NONE -> "— Не назначено —"
                else -> "${a.group.title}: ${a.short}  ${a.label}"
            }
        }.toTypedArray()
        val current = all.indexOf(map[key])
        val usedBy = map.actions.entries.associate { it.value to it.key }
        AlertDialog.Builder(this)
            .setTitle("SYM + ${AppLauncher.keyLabel(key)}")
            .setSingleChoiceItems(labels, current) { d, which ->
                val action = all[which]
                val other = usedBy[action]?.takeIf { it != key && action != SymPadAction.NONE }
                map = map.with(key, action)
                save()
                d.dismiss()
                if (other != null) toast("«${action.label}» теперь и на ${AppLauncher.keyLabel(other)}, и на ${AppLauncher.keyLabel(key)}")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save() {
        runCatching { store.saveSymPadMap(map) }.onFailure { toast("Не удалось сохранить: ${it.message}") }
        touch()
        render()
    }

    private fun editJson() {
        val field = EditText(this).apply {
            setText(map.toJson())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.TOP
        }
        val ids = SymPadAction.values().filter { it != SymPadAction.NONE }.joinToString(", ") { it.id }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(field)
            addView(text("Действия: $ids", 11f, 0.6f))
        }
        val dialog = AlertDialog.Builder(this).setTitle("JSON схемы").setView(box)
            .setPositiveButton("Применить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { store.saveSymPadJson(field.text.toString()) }
                    .onSuccess { map = it; touch(); render(); dialog.dismiss() }
                    .onFailure { toast("Ошибка в JSON: ${it.message}") }
            }
        }
        dialog.show()
    }

    private fun share() {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, map.toJson())
                .putExtra(Intent.EXTRA_SUBJECT, "sympad.json"),
            "Поделиться схемой SymPad"))
    }

    private fun reset() {
        AlertDialog.Builder(this).setMessage("Вернуть стандартную схему SymPad?")
            .setPositiveButton("Вернуть") { _, _ -> store.resetSymPadMap(); map = store.symPadMap(); touch(); render() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this).setMessage("Снять все назначения? Удобно, чтобы собрать свою схему с нуля.")
            .setPositiveButton("Очистить") { _, _ -> map = SymPadMap(emptyMap()); save() }
            .setNegativeButton("Отмена", null).show()
    }

    @Deprecated("Activity API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        runCatching {
            val json = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            store.saveSymPadJson(json)
        }.onSuccess { map = it; touch(); render(); toast("Схема загружена") }
            .onFailure { toast("Не похоже на схему SymPad: ${it.message}") }
    }

    /** The running keyboard reloads the scheme when preferences change. */
    private fun touch() = prefs.sp.edit().putLong("sympad_touched", System.currentTimeMillis()).apply()

    private fun text(s: String, sp: Float, a: Float) = TextView(this).apply { text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); alpha = a }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object { private const val REQ_IMPORT = 8 }
}
