package io.github.slrmoon.clack

import android.content.Context
import android.content.SharedPreferences
import clack.core.input.Shortcut

/** All user settings in one place. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("clack", Context.MODE_PRIVATE)

    enum class LongPress { OFF, UPPERCASE, ALT }

    /** Layout ids in cycling order. */
    var enabledLayouts: List<String>
        get() = (sp.getString(K_LAYOUTS, null) ?: DEFAULT_LAYOUTS).split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(K_LAYOUTS, v.joinToString(",")).apply()

    var currentLayout: String?
        get() = sp.getString(K_CURRENT, null)
        set(v) = sp.edit().putString(K_CURRENT, v).apply()

    /** Shortcuts that switch to the next layout. Empty string in storage = defaults. */
    var switchShortcuts: List<Shortcut>
        get() {
            val raw = sp.getString(K_SHORTCUT, "") ?: ""
            if (raw.isBlank()) return Shortcut.DEFAULTS
            return raw.split(';').mapNotNull { Shortcut.parse(it) }.ifEmpty { Shortcut.DEFAULTS }
        }
        set(v) = sp.edit().putString(K_SHORTCUT, v.joinToString(";") { it.serialize() }).apply()

    fun resetSwitchShortcut() = sp.edit().remove(K_SHORTCUT).apply()

    var suggestions: Boolean
        get() = sp.getBoolean(K_SUGGEST, true)
        set(v) = sp.edit().putBoolean(K_SUGGEST, v).apply()

    var autoCorrect: Boolean
        get() = sp.getBoolean(K_AUTOCORRECT, true)
        set(v) = sp.edit().putBoolean(K_AUTOCORRECT, v).apply()

    var autoCapitalize: Boolean
        get() = sp.getBoolean(K_AUTOCAP, true)
        set(v) = sp.edit().putBoolean(K_AUTOCAP, v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean(K_DOUBLE_SPACE, true)
        set(v) = sp.edit().putBoolean(K_DOUBLE_SPACE, v).apply()

    var learn: Boolean
        get() = sp.getBoolean(K_LEARN, true)
        set(v) = sp.edit().putBoolean(K_LEARN, v).apply()

    var longPress: LongPress
        get() = runCatching { LongPress.valueOf(sp.getString(K_LONG_PRESS, LongPress.ALT.name)!!) }.getOrDefault(LongPress.ALT)
        set(v) = sp.edit().putString(K_LONG_PRESS, v.name).apply()

    /** SYM, then a letter: open the app assigned to that key (or a list of apps on that letter). */
    var quickLaunch: Boolean
        get() = sp.getBoolean(K_QUICK_LAUNCH, true)
        set(v) = sp.edit().putBoolean(K_QUICK_LAUNCH, v).apply()

    /** Above the physical keyboard: full bar (language + suggestions), a thin colour strip, or nothing. */
    enum class BarMode { FULL, MINIMAL, HIDDEN }

    var barMode: BarMode
        get() {
            val v = sp.getString(K_BAR_MODE, null)
                ?: return if (sp.getBoolean(K_SHOW_BAR, true)) BarMode.FULL else BarMode.HIDDEN
            return runCatching { BarMode.valueOf(v) }.getOrDefault(BarMode.FULL)
        }
        set(v) = sp.edit().putString(K_BAR_MODE, v.name).apply()

    /** Something is shown above the keyboard (full bar or colour strip). */
    val showBar: Boolean get() = barMode != BarMode.HIDDEN

    /** Height of the colour strip in dp (≈6 dp per mm). */
    var ledHeightDp: Int
        get() = sp.getInt(K_LED_HEIGHT, 6)
        set(v) = sp.edit().putInt(K_LED_HEIGHT, v).apply()

    /** One colour for every indicator that is on (pressed / held / one-shot / automatic capital). */
    var ledActiveColor: Int
        get() = sp.getInt(K_LED_ACTIVE, 0xFF4CAF50.toInt())
        set(v) = sp.edit().putInt(K_LED_ACTIVE, v).apply()

    /** One colour for every locked indicator (double tap: Caps Lock, Alt/Ctrl lock, SymPad <S>). */
    var ledLockedColor: Int
        get() = sp.getInt(K_LED_LOCKED, 0xFFFF9800.toInt())
        set(v) = sp.edit().putInt(K_LED_LOCKED, v).apply()

    var ledOffColor: Int
        get() = sp.getInt(K_LED_OFF, 0x40808080)
        set(v) = sp.edit().putInt(K_LED_OFF, v).apply()

    /** Colour of the middle zone for a layout; defaults cycle through a palette by position. */
    fun layoutColor(id: String, index: Int): Int = sp.getInt("led_layout_$id", LAYOUT_PALETTE[index.mod(LAYOUT_PALETTE.size)])
    fun setLayoutColor(id: String, color: Int) = sp.edit().putInt("led_layout_$id", color).apply()

    fun resetLedColors() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("led_") && k != K_LED_HEIGHT) e.remove(k)
        e.apply()
    }

    enum class SymTap { SYMBOLS, APPS }

    /** What a SYM tap does inside a text field (outside text fields it always starts quick launch). */
    var symTapInText: SymTap
        get() = runCatching { SymTap.valueOf(sp.getString(K_SYM_TAP, SymTap.SYMBOLS.name)!!) }.getOrDefault(SymTap.SYMBOLS)
        set(v) = sp.edit().putString(K_SYM_TAP, v.name).apply()

    var symAutoClose: Boolean
        get() = sp.getBoolean(K_SYM_AUTOCLOSE, true)
        set(v) = sp.edit().putBoolean(K_SYM_AUTOCLOSE, v).apply()

    /** SYM opens emoji first instead of symbols. */
    var symEmojiFirst: Boolean
        get() = sp.getBoolean(K_SYM_EMOJI_FIRST, false)
        set(v) = sp.edit().putBoolean(K_SYM_EMOJI_FIRST, v).apply()

    /** ".,!?:;" typed right after a word get a space after them. */
    var spaceAfterPunctuation: Boolean
        get() = sp.getBoolean(K_SPACE_AFTER_PUNCT, true)
        set(v) = sp.edit().putBoolean(K_SPACE_AFTER_PUNCT, v).apply()

    enum class WordDelete { OFF, DOUBLE_PRESS, HOLD }

    /** How Backspace deletes whole words. Migrates the older on/off switch for double press. */
    var wordDelete: WordDelete
        get() {
            val v = sp.getString(K_WORD_DELETE, null)
            if (v == null) return if (sp.getBoolean(K_DOUBLE_BACKSPACE, false)) WordDelete.DOUBLE_PRESS else WordDelete.OFF
            return runCatching { WordDelete.valueOf(v) }.getOrDefault(WordDelete.OFF)
        }
        set(v) = sp.edit().putString(K_WORD_DELETE, v.name).apply()

    /** Max time between the two presses of a double Backspace. */
    var doubleBackspaceMs: Int
        get() = sp.getInt(K_DOUBLE_BACKSPACE_MS, 350)
        set(v) = sp.edit().putInt(K_DOUBLE_BACKSPACE_MS, v).apply()

    /** SymPad: SYM held + letter = navigation / media key. Off: SYM works as before. */
    var symPad: Boolean
        get() = sp.getBoolean(K_SYMPAD, false)
        set(v) = sp.edit().putBoolean(K_SYMPAD, v).apply()

    /** Long press SYM locks SymPad until the next SYM press. */
    var symPadLock: Boolean
        get() = sp.getBoolean(K_SYMPAD_LOCK, true)
        set(v) = sp.edit().putBoolean(K_SYMPAD_LOCK, v).apply()

    var symPadLongMs: Int
        get() = sp.getInt(K_SYMPAD_LONG_MS, 500)
        set(v) = sp.edit().putInt(K_SYMPAD_LONG_MS, v).apply()

    /** Outside text fields holding SYM + letter does SymPad instead of launching apps. */
    var symPadOutsideText: Boolean
        get() = sp.getBoolean(K_SYMPAD_OUTSIDE, false)
        set(v) = sp.edit().putBoolean(K_SYMPAD_OUTSIDE, v).apply()

    /** Short messages at the bottom of the screen: language, Shift/Alt/Ctrl, SymPad, shortcuts. Independent of the bar. */
    var notifications: Boolean
        get() = sp.getBoolean(K_NOTIFY, true)
        set(v) = sp.edit().putBoolean(K_NOTIFY, v).apply()

    var multiTapMs: Int
        get() = sp.getInt(K_MULTITAP, 450)
        set(v) = sp.edit().putInt(K_MULTITAP, v).apply()

    companion object {
        const val DEFAULT_LAYOUTS = "russian_translit,qwerty"
        private const val K_LAYOUTS = "layouts"
        private const val K_CURRENT = "current_layout"
        private const val K_SHORTCUT = "switch_shortcut"
        private const val K_SUGGEST = "suggestions"
        private const val K_AUTOCORRECT = "autocorrect"
        private const val K_AUTOCAP = "autocap"
        private const val K_DOUBLE_SPACE = "double_space"
        private const val K_LEARN = "learn"
        private const val K_LONG_PRESS = "long_press"
        private const val K_MULTITAP = "multitap_ms"
        private const val K_QUICK_LAUNCH = "quick_launch"
        private const val K_SHOW_BAR = "show_bar"
        private const val K_BAR_MODE = "bar_mode"
        private const val K_LED_HEIGHT = "led_height_dp"
        private const val K_LED_OFF = "led_off"
        private const val K_LED_ACTIVE = "led_active"
        private const val K_LED_LOCKED = "led_locked"
        private const val K_NOTIFY = "notifications"
        val LAYOUT_PALETTE = intArrayOf(
            0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEC407A.toInt(), 0xFF8D6E63.toInt(),
            0xFF5C6BC0.toInt(), 0xFF9CCC65.toInt(), 0xFFFFA726.toInt(), 0xFF78909C.toInt()
        )
        private const val K_SYMPAD = "sympad"
        private const val K_SYMPAD_LOCK = "sympad_lock"
        private const val K_SYMPAD_LONG_MS = "sympad_long_ms"
        private const val K_SYMPAD_OUTSIDE = "sympad_outside_text"
        private const val K_DOUBLE_BACKSPACE = "double_backspace_word"
        private const val K_WORD_DELETE = "word_delete"
        private const val K_DOUBLE_BACKSPACE_MS = "double_backspace_ms"
        private const val K_SPACE_AFTER_PUNCT = "space_after_punct"
        private const val K_SYM_EMOJI_FIRST = "sym_emoji_first"
        private const val K_SYM_TAP = "sym_tap_in_text"
        private const val K_SYM_AUTOCLOSE = "sym_autoclose"
    }
}
