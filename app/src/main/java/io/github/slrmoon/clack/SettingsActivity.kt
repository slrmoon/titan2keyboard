package io.github.slrmoon.clack

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import clack.core.input.Mod
import clack.core.input.Shortcut

/** Settings screen, built in code to keep the app small (no AndroidX). */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private lateinit var root: LinearLayout
    private lateinit var updater: Updater
    private var latest: clack.core.update.Release? = null
    private var updateStatus: String? = null
    private var updateBusy = false
    private var updateButton: Button? = null
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val dimColor get() = if (dark) Color.rgb(0x9A, 0x9A, 0xA0) else Color.rgb(0x60, 0x60, 0x66)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        prefs = Prefs(this)
        store = LayoutStore(this)
        updater = Updater(this)
        Languages.get(this).ensure("ru")
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
        if (!updateBusy && updater.shouldAutoCheck()) checkForUpdate(quiet = true)
    }

    private fun build() {
        root.removeAllViews()

        // --- last error, if any ---
        CrashLog.read(this)?.let { report ->
            section("Была ошибка")
            note(report.lineSequence().take(2).joinToString(" · "))
            row(
                button("Скопировать отчёт") {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Clack error", report))
                    toast("Отчёт скопирован — пришлите его разработчику")
                },
                button("Очистить") { CrashLog.clear(this); build() }
            )
        }

        // --- updates ---
        section("Версия ${updater.installedVersionName}")
        val rel = latest
        when {
            rel != null && rel.isNewerThan(updater.installedVersionCode) -> {
                note("Доступна версия ${rel.versionName}" + if (rel.notes.isNotBlank()) ":\n" + rel.notes.take(600) else "")
                updateButton = button(if (updateBusy) updateStatus ?: "Загрузка…" else "Обновить до ${rel.versionName}") { if (!updateBusy) startUpdate(rel) }
                row(updateButton!!)
            }
            else -> {
                updateStatus?.let { note(it) }
                row(button(if (updateBusy) "Проверяю…" else "Проверить обновления") { if (!updateBusy) checkForUpdate(quiet = false) })
            }
        }

        // --- enable ---
        val enabled = isEnabledIme()
        section("Клавиатура")
        if (!enabled) note("Clack ещё не включена. Включите её в списке клавиатур, затем выберите как основную.")
        row(
            button(if (enabled) "Список клавиатур" else "Включить Clack") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            button("Выбрать клавиатуру") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        )

        // --- layouts ---
        section("Раскладки")
        note("Формат JSON как у Pastiera: подходят её файлы и редактор pastierakeyedit.vercel.app. Отмеченные раскладки переключаются по кругу в этом порядке. ✎ — редактор, долгое нажатие на название — переименовать.")
        val all = store.available()
        val order = prefs.enabledLayouts.toMutableList()
        val sorted = all.sortedBy { e -> order.indexOf(e.id).let { if (it < 0) Int.MAX_VALUE else it } }
        for (e in sorted) root.addView(layoutRow(e, order))
        row(button("Создать раскладку…") { createLayout(all) }, button("Импорт JSON…") { pick(REQ_LAYOUT) })
        if (store.hasCustomAltLayer()) {
            row(button("Alt-слой: свой ✓  (сбросить)") { store.resetAltLayer(); touch(); build() })
        } else {
            row(button("Свой Alt-слой (JSON)…") { pick(REQ_ALT) })
        }

        // --- switching ---
        section("Смена раскладки")
        val current = prefs.switchShortcuts
        val label = if (current == Shortcut.DEFAULTS) "Ctrl+Space" else current.joinToString(", ") { it.label() }
        note("Сейчас: $label. Ctrl можно нажать отдельно: Ctrl, отпустить, затем пробел. Так же работают Ctrl, затем C / V / X / A (копировать, вставить, вырезать, выделить всё); двойное нажатие Ctrl фиксирует его. Ещё — тап по метке языка на полоске.")
        row(
            button("Записать сочетание") { recordShortcut() },
            button("Ctrl+Space") { prefs.resetSwitchShortcut(); build() }
        )

        // --- typing ---
        section("Полоска над клавиатурой")
        switch("Показывать полоску (язык и подсказки)", prefs.showBar) { prefs.showBar = it }
        note("Если полоска скрыта, смена языка и зафиксированные Shift/Alt/Ctrl показываются коротким сообщением внизу экрана. Подсказки тогда не видны, но автоисправление продолжает работать (его можно выключить ниже).")

        section("SYM: символы и эмодзи")
        note("В текстовом поле: SYM — первая страница, ещё раз SYM — вторая, ещё раз — закрыть. Символ вводится тапом или клавишей, на которой он показан. Панель открывается и при скрытой полоске.")
        spinner("SYM в текстовом поле", listOf("Символы и эмодзи", "Запуск приложений"),
            Prefs.SymTap.values().indexOf(prefs.symTapInText)) { prefs.symTapInText = Prefs.SymTap.values()[it] }
        spinner("Сначала показывать", listOf("Символы", "Эмодзи"), if (prefs.symEmojiFirst) 1 else 0) { prefs.symEmojiFirst = it == 1 }
        switch("Закрывать панель после ввода символа", prefs.symAutoClose) { prefs.symAutoClose = it }
        row(button("Редактировать SYM-панель") { startActivity(Intent(this, SymEditorActivity::class.java)) })

        section("Ввод")
        switch("Подсказки слов", prefs.suggestions) { prefs.suggestions = it }
        switch("Автоисправление на пробеле", prefs.autoCorrect) { prefs.autoCorrect = it }
        switch("Заглавная в начале предложения", prefs.autoCapitalize) { prefs.autoCapitalize = it }
        switch("Двойной пробел → точка", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        switch("Учиться на том, что я пишу", prefs.learn) { prefs.learn = it }
        spinner("Долгое нажатие на букву", listOf("Ничего", "Заглавная", "Символ Alt"),
            Prefs.LongPress.values().indexOf(prefs.longPress)) { prefs.longPress = Prefs.LongPress.values()[it] }
        val tapOptions = listOf(300, 450, 600, 800)
        spinner("Повторное нажатие (мульти-тап)", tapOptions.map { "$it мс" },
            tapOptions.indexOf(prefs.multiTapMs).coerceAtLeast(1)) { prefs.multiTapMs = tapOptions[it] }

        // --- quick launch ---
        section("Быстрый запуск приложений")
        note("Вне текстовых полей (рабочий стол и т.п.): нажмите SYM, отпустите и в течение 5 секунд нажмите букву. В текстовом поле: удерживайте SYM и нажмите букву. Если на неё назначено приложение — оно откроется, иначе появится список приложений на эту букву (в любой раскладке: T — и «Telegram», и «Еда»). Долгое нажатие в списке назначает приложение на букву. Работает и на рабочем столе.")
        switch("Включено", prefs.quickLaunch) { prefs.quickLaunch = it }
        val launcher = AppLauncher(this)
        val assignments = launcher.assignments()
        if (assignments.isEmpty()) note("Пока ничего не назначено.")
        for ((key, app) in assignments) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = "SYM+${AppLauncher.keyLabel(key)}  →  ${app.label}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(iconButton("✕") { launcher.unassign(key); build() })
            root.addView(line)
        }

        // --- learning ---
        section("Мой словарь")
        note("Импорт переписки из Telegram Desktop (Настройки → Продвинутые → Экспорт данных, формат JSON, файл result.json). Клавиатура запомнит ваши слова и какие слова вы пишете друг за другом. Всё остаётся на телефоне.")
        row(button("Импорт из Telegram…") { pick(REQ_TELEGRAM) })
        row(button("Забыть всё выученное") {
            AlertDialog.Builder(this)
                .setMessage("Удалить все выученные слова и сочетания?")
                .setPositiveButton("Удалить") { _, _ -> Languages.get(this).clearLearned(); toast("Готово") }
                .setNegativeButton("Отмена", null).show()
        })

        // --- try it ---
        section("Попробовать")
        root.addView(EditText(this).apply {
            hint = "Напишите что-нибудь…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun layoutRow(e: LayoutStore.Entry, order: MutableList<String>): View {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val check = CheckBox(this).apply {
            text = e.name + when (e.origin) {
                LayoutStore.Origin.MODIFIED -> "  (изменена)"
                LayoutStore.Origin.USER -> "  (своя)"
                else -> ""
            }
            isChecked = e.id in order
            setOnCheckedChangeListener { _, on ->
                val list = prefs.enabledLayouts.toMutableList()
                if (on && e.id !in list) list.add(e.id)
                if (!on) {
                    if (list.size <= 1) { isChecked = true; toast("Нужна хотя бы одна раскладка"); return@setOnCheckedChangeListener }
                    list.remove(e.id)
                }
                prefs.enabledLayouts = list
            }
        }
        check.setOnLongClickListener { rename(e.id); true }
        line.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (e.id in order) {
            line.addView(iconButton("↑") { move(e.id, -1) })
            line.addView(iconButton("↓") { move(e.id, +1) })
        }
        line.addView(iconButton("✎") { startActivity(LayoutEditorActivity.edit(this, e.id)) })
        when (e.origin) {
            LayoutStore.Origin.USER -> line.addView(iconButton("✕") {
                AlertDialog.Builder(this).setMessage("Удалить раскладку «${e.name}»?")
                    .setPositiveButton("Удалить") { _, _ ->
                        store.delete(e.id)
                        prefs.enabledLayouts = prefs.enabledLayouts.filter { it != e.id }.ifEmpty { listOf("qwerty") }
                        touch(); build()
                    }.setNegativeButton("Отмена", null).show()
            })
            LayoutStore.Origin.MODIFIED -> line.addView(iconButton("↺") {
                AlertDialog.Builder(this).setMessage("Вернуть исходную «${e.name}»? Ваши правки пропадут.")
                    .setPositiveButton("Вернуть") { _, _ -> store.delete(e.id); touch(); build() }
                    .setNegativeButton("Отмена", null).show()
            })
            else -> {}
        }
        return line
    }

    /** Change a layout's name and short label. For a built-in layout this saves an edited copy. */
    private fun rename(id: String) {
        val layout = store.load(id) ?: return
        val nameField = EditText(this).apply {
            setText(layout.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val labelField = EditText(this).apply {
            setText(layout.label ?: "")
            hint = "Метка на полоске, напр. ${layout.language.uppercase()}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(5))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameField); addView(labelField)
        }
        AlertDialog.Builder(this).setTitle("Переименовать раскладку").setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = nameField.text.toString().trim().ifEmpty { layout.name }
                val label = labelField.text.toString().trim().ifEmpty { null }
                runCatching { store.save(layout.copy(name = name, label = label)) }
                    .onSuccess { touch(); build() }
                    .onFailure { toast("Не удалось: ${it.message}") }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun createLayout(all: List<LayoutStore.Entry>) {
        val names = arrayOf("Пустая") + all.map { "Копия: ${it.name}" }
        AlertDialog.Builder(this).setTitle("Новая раскладка")
            .setItems(names) { _, which ->
                startActivity(LayoutEditorActivity.create(this, if (which == 0) null else all[which - 1].id))
            }.show()
    }

    private fun move(id: String, delta: Int) {
        val list = prefs.enabledLayouts.toMutableList()
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        prefs.enabledLayouts = list
        build()
    }

    private fun recordShortcut() {
        val msg = TextView(this).apply {
            text = "Нажмите сочетание на физической клавиатуре (например Ctrl+Space)…"
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Смена раскладки").setView(msg).setNegativeButton("Отмена", null).create()
        val held = HashSet<Mod>()
        dialog.setOnKeyListener { d, keyCode, event ->
            val mod = when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Mod.SHIFT
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Mod.ALT
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Mod.CTRL
                KeyEvent.KEYCODE_FUNCTION -> Mod.FN
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> Mod.META
                else -> null
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (mod != null) {
                if (event.action == KeyEvent.ACTION_DOWN) held.add(mod) else held.remove(mod)
                msg.text = (held.joinToString("+") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } } + "+…")
                return@setOnKeyListener true
            }
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            val mods = HashSet(held)
            if (event.isShiftPressed) mods.add(Mod.SHIFT)
            if (event.isAltPressed) mods.add(Mod.ALT)
            if (event.isCtrlPressed) mods.add(Mod.CTRL)
            if (event.isFunctionPressed) mods.add(Mod.FN)
            if (event.isMetaPressed) mods.add(Mod.META)
            if (mods.isEmpty()) {
                msg.text = "Нужна клавиша-модификатор (Fn, Alt, Ctrl или Shift) вместе с другой клавишей."
                return@setOnKeyListener true
            }
            val s = Shortcut(mods, KeyEvent.keyCodeToString(keyCode))
            prefs.switchShortcuts = listOf(s)
            toast("Смена раскладки: ${s.label()}")
            d.dismiss()
            build()
            true
        }
        dialog.show()
    }

    // --- file pickers ---

    private fun pick(request: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, request)
    }

    @Deprecated("Activity API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_LAYOUT -> runCatching { store.import(uri) }
                .onSuccess { id ->
                    prefs.enabledLayouts = (prefs.enabledLayouts + id).distinct()
                    toast("Раскладка добавлена"); build()
                }
                .onFailure { toast("Не похоже на раскладку: ${it.message}") }
            REQ_ALT -> runCatching { store.importAltLayer(uri) }
                .onSuccess { touch(); toast("Alt-слой загружен"); build() }
                .onFailure { toast("Не похоже на Alt-слой: ${it.message}") }
            REQ_TELEGRAM -> importTelegram(uri)
        }
    }

    private fun importTelegram(uri: Uri) {
        val status = TextView(this).apply { text = "Читаю переписку…"; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val dialog = AlertDialog.Builder(this).setTitle("Импорт из Telegram").setView(status).setCancelable(false).show()
        Thread {
            val result = runCatching {
                TelegramImporter(this, Languages.get(this)).import(uri) { n -> runOnUiThread { status.text = "Прочитано сообщений: $n" } }
            }
            runOnUiThread {
                dialog.dismiss()
                result.onSuccess { r ->
                    val who = if (r.ownOnly) "ваших сообщений" else "сообщений (в экспорте нет данных о владельце — учтены все)"
                    AlertDialog.Builder(this).setTitle("Готово")
                        .setMessage("Выучено ${r.words} слов из ${r.messages} $who.")
                        .setPositiveButton("OK", null).show()
                }.onFailure { toast("Не удалось прочитать файл: ${it.message}") }
            }
        }.start()
    }

    private fun checkForUpdate(quiet: Boolean) {
        updateBusy = true
        if (!quiet) build()
        Thread {
            val result = runCatching { updater.check() }
            runOnUiThread {
                updateBusy = false
                result.onSuccess { r ->
                    latest = r
                    updateStatus = if (r.isNewerThan(updater.installedVersionCode)) null else "Установлена последняя версия."
                }.onFailure { e ->
                    if (!quiet) updateStatus = "Не удалось проверить: ${e.message}"
                }
                if (!isFinishing) build()
            }
        }.start()
    }

    private fun startUpdate(release: clack.core.update.Release) {
        if (!updater.canInstall()) {
            AlertDialog.Builder(this)
                .setMessage("Разрешите Clack устанавливать обновления: на следующем экране включите «Разрешить установку из этого источника», затем вернитесь и нажмите «Обновить» ещё раз.")
                .setPositiveButton("Открыть") { _, _ -> startActivity(updater.installPermissionIntent()) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        updateBusy = true
        updateStatus = "Загрузка…"
        build()
        Thread {
            val result = runCatching {
                val apk = updater.download(release) { p -> runOnUiThread { updateStatus = "Загрузка $p%"; updateButton?.text = updateStatus } }
                updater.install(apk)
            }
            runOnUiThread {
                updateBusy = false
                updateStatus = result.exceptionOrNull()?.let { "Не удалось обновить: ${it.message}" }
                if (result.isSuccess) toast("Подтвердите установку в окне Android")
                if (!isFinishing) build()
            }
        }.start()
    }

    /** Make the running keyboard reload (it listens to preference changes). */
    private fun touch() = prefs.sp.edit().putLong("touched", System.currentTimeMillis()).apply()

    // --- tiny view helpers ---

    private fun isEnabledIme(): Boolean =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) Color.rgb(0x5E, 0x8B, 0xFF) else Color.rgb(0x2E, 0x5B, 0xFF))
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(dimColor)
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun row(vararg views: View) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { line.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }) }
        root.addView(line)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun iconButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        minWidth = dp(44); minimumWidth = dp(44)
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            text = label
            isChecked = value
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }

    private fun spinner(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected.coerceIn(0, options.size - 1), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onChange(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        root.addView(line)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()


    companion object {
        private const val REQ_LAYOUT = 1
        private const val REQ_ALT = 2
        private const val REQ_TELEGRAM = 3
    }
}
