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

    /** Thin strip with language and suggestions above the keyboard. */
    var showBar: Boolean
        get() = sp.getBoolean(K_SHOW_BAR, true)
        set(v) = sp.edit().putBoolean(K_SHOW_BAR, v).apply()

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
        private const val K_SPACE_AFTER_PUNCT = "space_after_punct"
        private const val K_SYM_EMOJI_FIRST = "sym_emoji_first"
        private const val K_SYM_TAP = "sym_tap_in_text"
        private const val K_SYM_AUTOCLOSE = "sym_autoclose"
    }
}
