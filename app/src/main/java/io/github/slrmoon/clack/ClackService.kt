package io.github.slrmoon.clack

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import clack.core.Candidate
import clack.core.CandidateKind
import clack.core.SuggestionEngine
import clack.core.Suggestions
import clack.core.input.Mod
import clack.core.launch.AppFilter
import clack.core.input.MultiTapTracker
import clack.core.input.StickyModifier
import clack.core.layout.AltLayer
import clack.core.layout.KeyboardLayout
import clack.core.predict.Tokenizer
import java.util.concurrent.TimeUnit

/**
 * Input method for phones with a hardware keyboard (built for the Unihertz Titan 2).
 *
 * Letters come from the active layout (Pastiera JSON), Alt gives the second layer
 * (digits/symbols), Shift and Alt are sticky (tap = next char, double tap = lock).
 * The word being typed is kept as composing text so a suggestion or auto-correction
 * can replace it in one step.
 */
class ClackService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private lateinit var languages: Languages
    private val main = Handler(Looper.getMainLooper())

    private var layouts: List<KeyboardLayout> = emptyList()
    private var layoutIndex = 0
    private val layout: KeyboardLayout? get() = layouts.getOrNull(layoutIndex)
    private var altLayer: AltLayer = AltLayer(emptyMap())

    private val shift = StickyModifier()
    private val alt = StickyModifier()
    /** Ctrl is sticky too: tap Ctrl, then Space switches layout, C/V/X/A copy/paste/cut/select all. */
    private val ctrl = StickyModifier()
    private var autoShift = false
    private val multiTap = MultiTapTracker()
    private var lastTapShift = false
    private var fnHeld = false

    private var bar: BarView? = null
    private var symPanel: SymPanelView? = null
    /** 0 = SYM panel closed, otherwise the page shown (1 symbols, 2 emoji). */
    private var symPage = 0
    private var symPages: List<AltLayer> = emptyList()
    private var symHeld = false
    private var symChorded = false
    private var lastToast: Toast? = null

    // --- state of the editor field ---
    private var field = Field.NONE
    private val composing = StringBuilder()
    /** Word right before the cursor that we show suggestions for without composing it (after backspace or cursor move). */
    private var peekWord: String? = null
    private var lastSpaceAuto = false        // space was inserted by a suggestion/auto-correction
    private var lastSpaceAt = 0L
    private var lastAutoCorrect: AutoCorrection? = null
    private var keepAsTyped: String? = null  // user undid a correction of this word: don't correct it again
    private val consumedKeys = HashSet<Int>()
    /** When we last changed the text ourselves; selection updates soon after are echoes of our own edits. */
    private var lastEditAt = 0L

    // --- suggestions ---
    private var generation = 0
    private var shown: Shown = Shown.EMPTY

    private data class AutoCorrection(val original: String, val corrected: String, val separator: String)

    private enum class Mode { COMPOSING, PEEK, NEXT }

    /** What the bar currently shows, so a tap knows what to insert. */
    private data class Shown(val mode: Mode, val forWord: String, val entries: List<Entry>, val result: Suggestions?) {
        data class Entry(val word: String, val keepTyped: Boolean)
        companion object { val EMPTY = Shown(Mode.NEXT, "", emptyList(), null) }
    }

    /** What kind of field we are typing into. */
    private data class Field(
        val suggest: Boolean, val autoCorrect: Boolean, val learn: Boolean, val compose: Boolean, val numeric: Boolean,
        /** No text editor (home screen, terminals, games): every key goes to the app untouched. */
        val raw: Boolean = false
    ) {
        companion object {
            val NONE = Field(false, false, false, false, false)
            val RAW = Field(false, false, false, false, false, raw = true)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        prefs = Prefs(this)
        store = LayoutStore(this)
        languages = Languages.get(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        reloadConfig()
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        languages.saveAsync()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        main.post { reloadConfig() }
    }

    private fun reloadConfig() {
        val ids = prefs.enabledLayouts
        layouts = ids.mapNotNull { store.load(it) }.ifEmpty { listOfNotNull(store.load("qwerty")) }
        layoutIndex = layouts.indexOfFirst { it.id == prefs.currentLayout }.coerceAtLeast(0)
        altLayer = store.altLayer()
        multiTap.timeoutMs = prefs.multiTapMs.toLong()
        layout?.let { languages.attachLayout(it) }
        // warm up the other languages too, so switching is instant
        layouts.map { it.language }.distinct().forEach { languages.ensure(it) }
        symPages = (1..LayoutStore.SYM_PAGES).map { store.symPage(it) }
        bar?.visibility = if (prefs.showBar) View.VISIBLE else View.GONE
        if (symPage > 0) renderSymPanel()
        updateInputViewShown()
        updateBarChrome()
    }

    override fun onCreateInputView(): View {
        val panel = SymPanelView(this,
            onSymbol = { s -> currentInputConnection?.let { insertFromSymPanel(it, s) } },
            onPage = { p -> symPage = p; renderSymPanel() },
            onClose = { closeSymPanel() })
        val v = BarView(this,
            onPick = ::pick,
            onLanguageTap = { switchLayout() },
            onLanguageLongPress = { openSettings() })
        bar = v
        symPanel = panel
        panel.visibility = View.GONE
        v.visibility = if (prefs.showBar) View.VISIBLE else View.GONE
        updateBarChrome()
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(panel)
            addView(v)
        }
    }

    /** Show the bar even though a hardware keyboard is attached — but only over real text fields. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return !field.raw && (prefs.showBar || symPage > 0)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing.setLength(0)
        peekWord = null
        lastAutoCorrect = null
        keepAsTyped = null
        lastSpaceAuto = false
        multiTap.reset()
        shift.reset(); alt.reset(); ctrl.reset()
        symPage = 0
        symHeld = false
        symPanel?.visibility = View.GONE
        field = classify(attribute)
        updateInputViewShown()
        updateAutoShift()
        showFor(Mode.NEXT)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composing.setLength(0)
        languages.saveAsync()
    }

    private fun classify(info: EditorInfo): Field {
        val type = info.inputType
        if (type == InputType.TYPE_NULL) return Field.RAW
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
            return Field(suggest = false, autoCorrect = false, learn = false, compose = false, numeric = true)
        }
        if (cls != InputType.TYPE_CLASS_TEXT) return Field.NONE
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val technical = variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val noSuggest = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val incognito = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val suggest = !password && !technical && prefs.suggestions
        return Field(
            suggest = suggest,
            autoCorrect = suggest && !noSuggest && prefs.autoCorrect,
            learn = suggest && !incognito && prefs.learn,
            compose = suggest,
            numeric = false
        )
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(keyCode)
        // SYM: symbol panel / quick launch. Works wherever the keyboard gets keys, text field or not.
        if (handleSym(keyCode, name, event)) {
            consumedKeys.add(keyCode)
            return true
        }
        val ic = currentInputConnection ?: return super.onKeyDown(keyCode, event)
        if (field.raw) return super.onKeyDown(keyCode, event)
        if (symPage > 0 && handleSymPanelKey(ic, keyCode, name, event)) {
            consumedKeys.add(keyCode)
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (event.repeatCount == 0) shift.onDown()
                return true
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                if (event.repeatCount == 0) alt.onDown()
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                if (event.repeatCount == 0) ctrl.onDown()
                return true
            }
            KeyEvent.KEYCODE_FUNCTION -> { fnHeld = true; return super.onKeyDown(keyCode, event) }
        }
        if (isModifierKey(keyCode)) return super.onKeyDown(keyCode, event)

        shift.onOtherKeyWhileHeld()
        alt.onOtherKeyWhileHeld()
        ctrl.onOtherKeyWhileHeld()

        // Layout switch shortcut (Ctrl+Space by default, configurable; Ctrl may be tapped first).
        val held = heldMods(event)
        if (prefs.switchShortcuts.any { it.matches(held, name) }) {
            if (event.repeatCount == 0) {
                switchLayout()
                ctrl.consumeOneShot()
                updateBarChrome()
            }
            consumedKeys.add(keyCode)
            return true
        }

        // Ctrl/Meta held down together with a key: the app handles it (copy, paste, select all, ...).
        if (event.isCtrlPressed || event.isMetaPressed) {
            finishComposing(ic)
            return super.onKeyDown(keyCode, event)
        }

        // Ctrl tapped before this key: send the combination ourselves.
        if (ctrl.mode != StickyModifier.Mode.OFF) {
            sendCtrlCombo(ic, keyCode)
            ctrl.consumeOneShot()
            updateBarChrome()
            consumedKeys.add(keyCode)
            return true
        }

        val handled = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> { if (event.repeatCount == 0) onSpace(ic, event.eventTime); true }
            KeyEvent.KEYCODE_DEL -> onBackspace(ic, event)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { commitWord(ic, autoCorrect = false); updateAutoShiftLater(); false }
            else -> onCharacterKey(ic, keyCode, name, event)
        }
        if (handled) consumedKeys.add(keyCode)
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SYM && consumedKeys.remove(keyCode)) {
            symHeld = false
            if (!symChorded) onSymTap()
            return true
        }
        if (consumedKeys.remove(keyCode)) return true
        if (field.raw) return super.onKeyUp(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                val before = shift.mode
                shift.onUp(event.eventTime)
                if (autoShift && before == StickyModifier.Mode.OFF && shift.mode == StickyModifier.Mode.ONESHOT) {
                    // automatic capital was pending: a Shift tap cancels it instead of adding another
                    shift.reset()
                    autoShift = false
                } else if (shift.mode == StickyModifier.Mode.OFF) autoShift = false
                if (before != shift.mode && (before == StickyModifier.Mode.LOCKED || shift.mode == StickyModifier.Mode.LOCKED))
                    notifyIfNoBar(if (shift.mode == StickyModifier.Mode.LOCKED) "⇪ Caps Lock" else "Caps Lock выкл")
                updateBarChrome()
                return true
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                val before = alt.mode
                alt.onUp(event.eventTime)
                if (before != alt.mode) notifyIfNoBar(modeText("Alt", alt.mode))
                updateBarChrome()
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                val before = ctrl.mode
                ctrl.onUp(event.eventTime)
                if (before != ctrl.mode) notifyIfNoBar(modeText("Ctrl", ctrl.mode))
                updateBarChrome()
                return true
            }
            KeyEvent.KEYCODE_FUNCTION -> fnHeld = false
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ SYM + letter quick launch

    private var symArmed = false
    private val symTimeout = Runnable { disarmSym() }
    private val symHint = Runnable {
        if (symArmed) Toast.makeText(this, "SYM: нажмите букву приложения", Toast.LENGTH_SHORT).show()
    }

    /**
     * SYM handling. The SYM key itself acts on release (see onKeyUp) so that holding SYM and
     * pressing a letter can launch an app without opening the panel. Returns true when used.
     */
    private fun handleSym(keyCode: Int, name: String, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SYM) {
            if (event.repeatCount == 0) { symHeld = true; symChorded = false }
            return true
        }
        if (symHeld && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            if (event.repeatCount == 0) {
                symChorded = true
                if (prefs.quickLaunch) {
                    currentInputConnection?.let { if (!field.raw) finishComposing(it) }
                    closeSymPanel()
                    quickLaunch(name)
                }
            }
            return true
        }
        if (!symArmed) return false
        if (isModifierKey(keyCode) || keyCode in SHIFT_ALT_FN) return false // keep waiting for the letter
        if (event.repeatCount > 0) return true
        disarmSym()
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            currentInputConnection?.let { if (!field.raw) finishComposing(it) }
            quickLaunch(name)
            return true
        }
        // Esc or Space just cancel; any other key cancels and then works as usual
        return keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_SPACE
    }

    private fun armSym() {
        symArmed = true
        main.removeCallbacks(symTimeout); main.removeCallbacks(symHint)
        main.postDelayed(symTimeout, SYM_TIMEOUT_MS)
        main.postDelayed(symHint, SYM_HINT_DELAY_MS)
        updateBarChrome()
    }

    private fun disarmSym() {
        symArmed = false
        main.removeCallbacks(symTimeout); main.removeCallbacks(symHint)
        updateBarChrome()
    }

    /** SYM pressed and released on its own. */
    private fun onSymTap() {
        val inText = !field.raw && currentInputConnection != null
        if (inText && prefs.symTapInText == Prefs.SymTap.SYMBOLS) {
            disarmSym()
            symPage = if (symPage >= LayoutStore.SYM_PAGES) 0 else symPage + 1
            renderSymPanel()
        } else if (prefs.quickLaunch) {
            closeSymPanel()
            if (symArmed) disarmSym() else armSym()
        }
    }

    // ------------------------------------------------------------------ SYM panel

    private fun renderSymPanel() {
        val panel = symPanel
        if (symPage <= 0) {
            panel?.visibility = View.GONE
        } else if (panel != null) {
            panel.show(symPage, LayoutStore.SYM_PAGE_NAMES, symPages.getOrElse(symPage - 1) { AltLayer(emptyMap()) })
            panel.visibility = View.VISIBLE
        }
        updateInputViewShown()
    }

    private fun closeSymPanel() {
        if (symPage == 0) return
        symPage = 0
        renderSymPanel()
    }

    /** Keys while the SYM panel is open. Returns true when the key was used by the panel. */
    private fun handleSymPanelKey(ic: InputConnection, keyCode: Int, name: String, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { closeSymPanel(); return true }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> return false
        }
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val sym = symPages.getOrNull(symPage - 1)?.get(name)
            if (event.repeatCount == 0 && sym != null) insertFromSymPanel(ic, sym)
            return true
        }
        // anything else (space, enter, arrows): close the panel and handle the key normally
        closeSymPanel()
        return false
    }

    private fun insertFromSymPanel(ic: InputConnection, s: String) {
        insertSymbol(ic, s)
        if (prefs.symAutoClose) closeSymPanel()
    }

    // ------------------------------------------------------------------ Ctrl

    /** Ctrl was tapped before [keyCode]: perform Ctrl+key in the app. */
    private fun sendCtrlCombo(ic: InputConnection, keyCode: Int) {
        finishComposing(ic)
        markEdit()
        val menuAction = when (keyCode) {
            KeyEvent.KEYCODE_C -> android.R.id.copy
            KeyEvent.KEYCODE_X -> android.R.id.cut
            KeyEvent.KEYCODE_V -> android.R.id.paste
            KeyEvent.KEYCODE_A -> android.R.id.selectAll
            else -> null
        }
        if (menuAction != null && ic.performContextMenuAction(menuAction)) return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift.active) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        shift.consumeOneShot()
    }

    // ------------------------------------------------------------------ notifications

    private fun modeText(name: String, mode: StickyModifier.Mode) = when (mode) {
        StickyModifier.Mode.ONESHOT -> name
        StickyModifier.Mode.LOCKED -> "$name зафиксирован"
        StickyModifier.Mode.OFF -> "$name выкл"
    }

    /** With the bar hidden, short messages at the bottom of the screen tell what changed. */
    private fun notifyIfNoBar(text: String) {
        if (prefs.showBar) return
        lastToast?.cancel()
        lastToast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun quickLaunch(keyName: String) {
        val launcher = AppLauncher(this)
        val app = launcher.assigned(keyName)
        if (app != null && launcher.launch(app)) return
        val letters = AppFilter.lettersForKey(keyName, layouts.ifEmpty { listOfNotNull(layout) })
        runCatching { startActivity(AppPickerActivity.intent(this, keyName, letters)) }
            .onFailure { Toast.makeText(this, "Не удалось открыть список приложений", Toast.LENGTH_SHORT).show() }
    }

    private fun isModifierKey(k: Int) = k == KeyEvent.KEYCODE_CTRL_LEFT || k == KeyEvent.KEYCODE_CTRL_RIGHT ||
        k == KeyEvent.KEYCODE_META_LEFT || k == KeyEvent.KEYCODE_META_RIGHT || k == KeyEvent.KEYCODE_SYM ||
        k == KeyEvent.KEYCODE_CAPS_LOCK

    private fun heldMods(e: KeyEvent): Set<Mod> {
        val s = HashSet<Mod>()
        if (e.isShiftPressed || shift.held) s.add(Mod.SHIFT)
        if (e.isAltPressed || alt.held) s.add(Mod.ALT)
        if (e.isCtrlPressed || ctrl.active) s.add(Mod.CTRL)
        if (e.isFunctionPressed || fnHeld) s.add(Mod.FN)
        if (e.isMetaPressed) s.add(Mod.META)
        return s
    }

    private fun shiftActive() = shift.active || autoShift
    private fun altActive(ev: KeyEvent) = alt.active || ev.isAltPressed || field.numeric

    private fun onCharacterKey(ic: InputConnection, keyCode: Int, name: String, event: KeyEvent): Boolean {
        val mapping = layout?.mappings?.get(name)

        // Holding a letter: second meaning instead of repeating.
        if (event.repeatCount > 0) {
            if (mapping == null && altLayer[name] == null) return false
            if (event.repeatCount == 1) onLongPress(ic, name, mapping != null && !altActive(event))
            return true
        }

        if (altActive(event)) {
            val sym = altLayer[name]
                ?: event.getUnicodeChar(KeyEvent.META_ALT_ON).takeIf { it > 0 }?.let { String(Character.toChars(it)) }
            if (sym != null) {
                insertSymbol(ic, sym)
                alt.consumeOneShot()
                updateBarChrome()
                return true
            }
        }

        if (mapping != null) {
            val tap = multiTap.onKey(name, mapping.outputs.size, event.eventTime)
            val upper = if (tap.replacesPrevious) lastTapShift else shiftActive()
            val out = mapping.outputs[tap.index].forShift(upper)
            if (tap.replacesPrevious) {
                val prev = mapping.outputs[(tap.index - 1 + mapping.outputs.size) % mapping.outputs.size].forShift(upper)
                replaceLastTyped(ic, prev, out)
            } else {
                lastTapShift = upper
                typeLetters(ic, out)
                shift.consumeOneShot()
                autoShift = false
                updateBarChrome()
            }
            return true
        }

        // Keys outside the layout (punctuation keys on other devices): use what the system says they type.
        val uc = event.getUnicodeChar(event.metaState)
        if (uc > 0 && !Character.isISOControl(uc)) {
            insertSymbol(ic, String(Character.toChars(uc)))
            return true
        }
        finishComposing(ic)
        return false
    }

    private fun onLongPress(ic: InputConnection, name: String, wasLetter: Boolean) {
        if (!wasLetter) return
        val replacement = when (prefs.longPress) {
            Prefs.LongPress.OFF -> return
            Prefs.LongPress.UPPERCASE -> layout?.mappings?.get(name)?.primary?.upper ?: return
            Prefs.LongPress.ALT -> altLayer[name] ?: return
        }
        multiTap.reset()
        val typed = layout?.mappings?.get(name)?.primary?.forShift(lastTapShift) ?: return
        if (prefs.longPress == Prefs.LongPress.UPPERCASE) {
            replaceLastTyped(ic, typed, replacement)
        } else {
            // the letter goes away, the symbol is inserted like an Alt symbol
            removeLastTyped(ic, typed)
            insertSymbol(ic, replacement)
        }
    }

    // ------------------------------------------------------------------ text editing

    private fun typeLetters(ic: InputConnection, s: String) {
        markEdit()
        lastSpaceAuto = false
        lastAutoCorrect = null
        if (!field.compose) {
            ic.commitText(s, 1)
            return
        }
        if (composing.isEmpty()) adoptWordBeforeCursor(ic)
        composing.append(s)
        ic.setComposingText(composing, 1)
        requestSuggestions()
    }

    private fun replaceLastTyped(ic: InputConnection, prev: String, next: String) {
        markEdit()
        if (field.compose && composing.endsWith(prev)) {
            composing.setLength(composing.length - prev.length)
            composing.append(next)
            ic.setComposingText(composing, 1)
            requestSuggestions()
        } else {
            val before = ic.getTextBeforeCursor(prev.length, 0)
            if (before != null && before.toString() == prev) ic.deleteSurroundingText(prev.length, 0)
            ic.commitText(next, 1)
        }
    }

    private fun removeLastTyped(ic: InputConnection, prev: String) {
        markEdit()
        if (field.compose && composing.endsWith(prev)) {
            composing.setLength(composing.length - prev.length)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
        } else {
            val before = ic.getTextBeforeCursor(prev.length, 0)
            if (before != null && before.toString() == prev) ic.deleteSurroundingText(prev.length, 0)
        }
    }

    /** If the cursor sits right after a word, continue editing that word instead of starting a new one. */
    private fun adoptWordBeforeCursor(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        if (after.isNotEmpty() && Tokenizer.isWordChar(after[0])) return
        var i = before.length
        while (i > 0 && (Tokenizer.isWordChar(before[i - 1]) || (before[i - 1] == '-' && i > 1 && before[i - 2].isLetter()))) i--
        val word = before.substring(i)
        if (word.isEmpty() || word.length >= 48) return
        ic.deleteSurroundingText(word.length, 0)
        composing.append(word)
        peekWord = null
    }

    private fun insertSymbol(ic: InputConnection, s: String) {
        markEdit()
        multiTap.reset()
        // hyphen and apostrophe inside a word belong to the word ("кто-то", "don't")
        if ((s == "-" || s == "'" || s == "’") && composing.isNotEmpty() && field.compose) {
            composing.append(s)
            ic.setComposingText(composing, 1)
            requestSuggestions()
            return
        }
        val punct = s.length == 1 && s[0] in ".,!?:;"
        if (composing.isNotEmpty()) commitWord(ic, autoCorrect = punct)
        if (punct && lastSpaceAuto) {
            // "слово ," → "слово, "
            val before = ic.getTextBeforeCursor(1, 0)
            if (before?.toString() == " ") {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("$s ", 1)
                lastSpaceAuto = true
                afterCommit(ic)
                return
            }
        }
        ic.commitText(s, 1)
        lastSpaceAuto = false
        afterCommit(ic)
    }

    private fun onSpace(ic: InputConnection, now: Long) {
        markEdit()
        multiTap.reset()
        // double space → ". "
        if (prefs.doubleSpacePeriod && composing.isEmpty() && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before != null && before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in ")\"»")) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
                lastSpaceAuto = false
                afterCommit(ic)
                return
            }
        }
        val hadWord = composing.isNotEmpty()
        commitWord(ic, autoCorrect = true, separator = " ")
        if (!hadWord) ic.commitText(" ", 1)
        lastSpaceAt = now
        lastSpaceAuto = false
        afterCommit(ic)
    }

    private fun onBackspace(ic: InputConnection, event: KeyEvent): Boolean {
        multiTap.reset()
        if (composing.isNotEmpty()) {
            markEdit()
            val cut = Character.charCount(composing.codePointBefore(composing.length))
            composing.setLength(composing.length - cut)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
                showFor(Mode.NEXT)
            } else {
                ic.setComposingText(composing, 1)
                requestSuggestions()
            }
            return true
        }
        // Backspace right after an auto-correction undoes it.
        val ac = lastAutoCorrect
        if (ac != null && event.repeatCount == 0) {
            val tail = ac.corrected + ac.separator
            val before = ic.getTextBeforeCursor(tail.length, 0)?.toString()
            lastAutoCorrect = null
            if (before == tail) {
                markEdit()
                ic.deleteSurroundingText(tail.length, 0)
                keepAsTyped = Tokenizer.normalize(ac.original)
                if (field.compose) {
                    composing.append(ac.original)
                    ic.setComposingText(composing, 1)
                    requestSuggestions()
                } else ic.commitText(ac.original, 1)
                return true
            }
        }
        lastSpaceAuto = false
        // Let the app delete (handles selections, key repeat, emoji). The resulting selection
        // update is not ours, so onUpdateSelection refreshes suggestions for the word at the cursor.
        return false
    }

    /** Commit the word being composed, auto-correcting it if allowed. */
    private fun commitWord(ic: InputConnection, autoCorrect: Boolean, separator: String = "") {
        markEdit()
        if (composing.isEmpty()) {
            if (separator.isNotEmpty()) ic.commitText(separator, 1)
            return
        }
        val typed = composing.toString()
        var final = typed
        if (autoCorrect && field.autoCorrect && Tokenizer.normalize(typed) != keepAsTyped) {
            val result = suggestionsNow(typed)
            val fix = result?.autoCorrect
            if (fix != null) final = SuggestionEngine.applyCasing(fix.word, typed)
        }
        composing.setLength(0)
        ic.commitText(final + separator, 1)
        lastAutoCorrect = if (final != typed) AutoCorrection(typed, final, separator) else null
        val kept = keepAsTyped
        if (kept != null && final == typed && Tokenizer.normalize(typed) == kept) {
            // The user undid the correction and kept their word: remember it. Pass the value, not the
            // field — this runs later on another thread, after keepAsTyped has been cleared.
            withLang { it.user.addWord(kept) }
            keepAsTyped = null
        }
        learn(final, contextFor(ic, excludeTail = final.length + separator.length))
    }

    private fun markEdit() { lastEditAt = SystemClock.uptimeMillis() }

    private fun finishComposing(ic: InputConnection) {
        if (composing.isEmpty()) return
        val w = composing.toString()
        composing.setLength(0)
        ic.finishComposingText()
        learn(w, contextFor(ic, excludeTail = w.length))
    }

    private fun afterCommit(ic: InputConnection) {
        updateAutoShift()
        showFor(Mode.NEXT)
    }

    private fun learn(word: String, context: List<String>) {
        if (!field.learn) return
        val w = Tokenizer.normalize(word)
        if (w.isEmpty() || w.any { !Tokenizer.isWordChar(it) && it != '-' }) return
        withLang { lang ->
            lang.user.learn(context, w)
            lang.engine.refreshUserIndex()
        }
    }

    /** Previous words of the current sentence, normalized; the last [excludeTail] chars before the cursor are skipped. */
    private fun contextFor(ic: InputConnection, excludeTail: Int): List<String> {
        val before = ic.getTextBeforeCursor(160, 0)?.toString() ?: return emptyList()
        val t = before.dropLast(excludeTail.coerceAtMost(before.length))
        val trimmed = t.trimEnd()
        if (trimmed.isEmpty() || trimmed.last() in ".!?\n…") return emptyList()
        return Tokenizer.sentences(t).lastOrNull()?.takeLast(2) ?: emptyList()
    }

    private fun updateAutoShift() {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        autoShift = prefs.autoCapitalize && ic != null && info != null && composing.isEmpty() &&
            info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            ic.getCursorCapsMode(info.inputType) != 0
        updateBarChrome()
    }

    private fun updateAutoShiftLater() = main.post { updateAutoShift(); showFor(Mode.NEXT) }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candStart: Int, candEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd)
        val ours = SystemClock.uptimeMillis() - lastEditAt < OWN_EDIT_ECHO_MS
        if (composing.isNotEmpty()) {
            val moved = if (candEnd < 0) !ours else newSelStart != candEnd || newSelEnd != candEnd
            if (!moved) return
            // the user put the cursor somewhere else: keep the text as it is
            currentInputConnection?.finishComposingText()
            composing.setLength(0)
        } else if (ours || newSelStart == oldSelStart && newSelEnd == oldSelEnd) {
            return
        }
        lastAutoCorrect = null
        lastSpaceAuto = false
        multiTap.reset()
        updateAutoShift()
        if (newSelStart == newSelEnd) refreshPeek() else showFor(Mode.NEXT)
    }

    // ------------------------------------------------------------------ suggestions

    private fun withLang(block: (Languages.Lang) -> Unit) {
        val code = layout?.language ?: return
        languages.worker.execute { languages.get(code)?.let(block) }
    }

    /** Synchronous lookup for auto-correction on space; gives up quickly if the dictionary is still loading. */
    private fun suggestionsNow(typed: String): Suggestions? {
        if (shown.mode == Mode.COMPOSING && shown.forWord == typed && shown.result != null) return shown.result
        val code = layout?.language ?: return null
        val ic = currentInputConnection ?: return null
        val ctx = contextFor(ic, excludeTail = typed.length)
        return runCatching {
            languages.worker.submit<Suggestions?> { languages.get(code)?.engine?.suggest(ctx, typed) }.get(250, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun requestSuggestions() = showFor(Mode.COMPOSING)

    private fun refreshPeek() {
        val ic = currentInputConnection ?: return
        if (!field.suggest || composing.isNotEmpty()) return
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        var i = before.length
        while (i > 0 && Tokenizer.isWordChar(before[i - 1])) i--
        val word = before.substring(i)
        if (word.isNotEmpty() && (after.isEmpty() || !Tokenizer.isWordChar(after[0]))) {
            peekWord = word
            showFor(Mode.PEEK)
        } else {
            peekWord = null
            showFor(Mode.NEXT)
        }
    }

    private fun showFor(mode: Mode) {
        val gen = ++generation
        val ic = currentInputConnection
        if (!field.suggest || ic == null) { applyShown(Shown.EMPTY); return }
        val word = when (mode) {
            Mode.COMPOSING -> composing.toString()
            Mode.PEEK -> peekWord ?: ""
            Mode.NEXT -> ""
        }
        if (mode != Mode.NEXT && word.isEmpty()) { applyShown(Shown.EMPTY); return }
        val ctx = contextFor(ic, excludeTail = word.length)
        val capitalizeNext = autoShift || shift.active
        val code = layout?.language ?: return
        languages.ensure(code)
        languages.worker.execute {
            val lang = languages.get(code) ?: return@execute
            val result: Shown = if (mode == Mode.NEXT) {
                val preds = lang.engine.predictNext(ctx)
                Shown(mode, "", preds.map { Shown.Entry(SuggestionEngine.applyCasing(it.word, "", capitalizeNext), false) }, null)
            } else {
                val s = lang.engine.suggest(ctx, word)
                val entries = ArrayList<Shown.Entry>()
                if (s.unknownTyped != null) entries.add(Shown.Entry(word, keepTyped = true))
                for (c in s.items) {
                    if (entries.size >= 3) break
                    val cased = SuggestionEngine.applyCasing(c.word, word)
                    if (entries.none { it.word == cased }) entries.add(Shown.Entry(cased, keepTyped = c.kind == CandidateKind.TYPED))
                }
                Shown(mode, word, entries, s)
            }
            main.post { if (gen == generation) applyShown(result) }
        }
    }

    private fun applyShown(s: Shown) {
        shown = s
        val auto = s.result?.autoCorrect?.word
        bar?.setItems(s.entries.map { e ->
            BarView.Item(
                text = e.word,
                emphasized = field.autoCorrect && auto != null && Tokenizer.normalize(e.word) == auto && !e.keepTyped,
                quoted = e.keepTyped && s.result?.unknownTyped != null && Tokenizer.normalize(e.word) == s.result.unknownTyped
            )
        })
    }

    private fun pick(index: Int) {
        markEdit()
        val ic = currentInputConnection ?: return
        val entry = shown.entries.getOrNull(index) ?: return
        multiTap.reset()
        when (shown.mode) {
            Mode.COMPOSING -> {
                if (composing.isEmpty()) return
                composing.setLength(0)
                ic.commitText(entry.word + " ", 1)
                if (entry.keepTyped) withLang { it.user.addWord(Tokenizer.normalize(entry.word)) }
                learn(entry.word, contextFor(ic, excludeTail = entry.word.length + 1))
            }
            Mode.PEEK -> {
                val old = peekWord ?: return
                val before = ic.getTextBeforeCursor(old.length, 0)?.toString()
                if (before != old) return
                ic.deleteSurroundingText(old.length, 0)
                val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
                ic.commitText(entry.word + if (after.startsWith(" ")) "" else " ", 1)
                if (entry.keepTyped) withLang { it.user.addWord(Tokenizer.normalize(entry.word)) }
                peekWord = null
            }
            Mode.NEXT -> {
                ic.commitText(entry.word + " ", 1)
                learn(entry.word, contextFor(ic, excludeTail = entry.word.length + 1))
            }
        }
        lastAutoCorrect = null
        lastSpaceAuto = true
        shift.consumeOneShot()
        afterCommit(ic)
    }

    // ------------------------------------------------------------------ layout & chrome

    private fun switchLayout() {
        if (layouts.size < 2) return
        currentInputConnection?.let { finishComposing(it) }
        layoutIndex = (layoutIndex + 1) % layouts.size
        multiTap.reset()
        layout?.let {
            prefs.currentLayout = it.id // triggers reloadConfig via the listener; index is preserved
            languages.attachLayout(it)
            notifyIfNoBar("${it.shortLabel} · ${it.name}")
        }
        updateBarChrome()
        showFor(Mode.NEXT)
    }

    private fun updateBarChrome() {
        val b = bar ?: return
        b.setLanguage(layout?.shortLabel ?: "--")
        val parts = ArrayList<String>()
        when {
            shift.mode == StickyModifier.Mode.LOCKED -> parts.add("⇪")
            shift.active || autoShift -> parts.add("⇧")
        }
        if (symArmed) parts.add("SYM")
        when (ctrl.mode) {
            StickyModifier.Mode.LOCKED -> parts.add("CTRL")
            StickyModifier.Mode.ONESHOT -> parts.add("ctrl")
            else -> {}
        }
        when (alt.mode) {
            StickyModifier.Mode.LOCKED -> parts.add("ALT")
            StickyModifier.Mode.ONESHOT -> parts.add("alt")
            else -> if (field.numeric) parts.add("123")
        }
        b.setModifiers(parts.joinToString(" "))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("unused")
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        private const val SYM_TIMEOUT_MS = 5000L
        private const val SYM_HINT_DELAY_MS = 500L
        private val SHIFT_ALT_FN = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_FUNCTION
        )
        private const val OWN_EDIT_ECHO_MS = 400L
    }
}
