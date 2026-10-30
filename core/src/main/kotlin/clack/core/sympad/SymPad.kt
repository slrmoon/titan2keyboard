package clack.core.sympad

import clack.core.Json
import clack.core.layout.LayoutEditing

/**
 * SymPad: letter keys used together with SYM as navigation, editing and media keys.
 *
 * Each action describes the Android key it emulates. Android key codes are repeated here as
 * constants so the core stays free of Android classes (values from android.view.KeyEvent).
 */
enum class SymPadAction(
    val id: String, val label: String, val short: String, val group: Group,
    val key: Int = 0, val ctrl: Boolean = false, val shift: Boolean = false
) {
    NONE("none", "Не назначено", "", Group.NONE),

    UP("up", "Курсор вверх", "↑", Group.NAVIGATION, K.DPAD_UP),
    DOWN("down", "Курсор вниз", "↓", Group.NAVIGATION, K.DPAD_DOWN),
    LEFT("left", "Курсор влево", "←", Group.NAVIGATION, K.DPAD_LEFT),
    RIGHT("right", "Курсор вправо", "→", Group.NAVIGATION, K.DPAD_RIGHT),
    WORD_LEFT("word_left", "Слово влево", "⇠", Group.NAVIGATION, K.DPAD_LEFT, ctrl = true),
    WORD_RIGHT("word_right", "Слово вправо", "⇢", Group.NAVIGATION, K.DPAD_RIGHT, ctrl = true),
    LINE_START("home", "Начало строки (Home)", "⇤", Group.NAVIGATION, K.MOVE_HOME),
    LINE_END("end", "Конец строки (End)", "⇥", Group.NAVIGATION, K.MOVE_END),
    DOC_START("doc_start", "Начало текста (Ctrl+Home)", "⤒", Group.NAVIGATION, K.MOVE_HOME, ctrl = true),
    DOC_END("doc_end", "Конец текста (Ctrl+End)", "⤓", Group.NAVIGATION, K.MOVE_END, ctrl = true),
    PAGE_UP("page_up", "Страница вверх", "PgU", Group.NAVIGATION, K.PAGE_UP),
    PAGE_DOWN("page_down", "Страница вниз", "PgD", Group.NAVIGATION, K.PAGE_DOWN),

    COPY("copy", "Копировать (Ctrl+C)", "⧉", Group.EDIT, K.C, ctrl = true),
    CUT("cut", "Вырезать (Ctrl+X)", "✂", Group.EDIT, K.X, ctrl = true),
    PASTE("paste", "Вставить (Ctrl+V)", "📋", Group.EDIT, K.V, ctrl = true),
    SELECT_ALL("select_all", "Выделить всё (Ctrl+A)", "▣", Group.EDIT, K.A, ctrl = true),
    UNDO("undo", "Отменить (Ctrl+Z)", "↶", Group.EDIT, K.Z, ctrl = true),
    REDO("redo", "Повторить (Ctrl+Shift+Z)", "↷", Group.EDIT, K.Z, ctrl = true, shift = true),
    FIND("find", "Поиск (Ctrl+F)", "🔍", Group.EDIT, K.F, ctrl = true),
    SAVE("save", "Сохранить (Ctrl+S)", "💾", Group.EDIT, K.S, ctrl = true),
    DELETE_WORD("delete_word", "Удалить слово слева", "⌫w", Group.EDIT),
    BACKSPACE("backspace", "Backspace", "⌫", Group.EDIT, K.DEL),
    DELETE_FORWARD("delete_forward", "Удалить справа (Del)", "Del", Group.EDIT, K.FORWARD_DEL),
    ENTER("enter", "Enter", "⏎", Group.EDIT, K.ENTER),
    SPACE("space", "Пробел", "␣", Group.EDIT, K.SPACE),

    SELECT("select", "Выбрать / OK (центр крестовины)", "OK", Group.KEYS, K.DPAD_CENTER),
    TAB("tab", "Tab", "Tab", Group.KEYS, K.TAB),
    SHIFT_TAB("shift_tab", "Shift+Tab (назад по полям)", "⇤Tab", Group.KEYS, K.TAB, shift = true),
    ESCAPE("escape", "Esc", "Esc", Group.KEYS, K.ESCAPE),
    BACK("back", "Назад (в приложении)", "◁", Group.KEYS, K.BACK),
    MENU("menu", "Меню", "☰", Group.KEYS, K.MENU),
    SEARCH("search", "Search", "Srch", Group.KEYS, K.SEARCH),
    INSERT("insert", "Insert", "Ins", Group.KEYS, K.INSERT),
    PRINT_SCREEN("print_screen", "PrintScreen / SysRq", "PrtSc", Group.KEYS, K.SYSRQ),
    PAUSE_BREAK("pause_break", "Pause / Break", "Brk", Group.KEYS, K.BREAK),
    SCROLL_LOCK("scroll_lock", "Scroll Lock", "ScrL", Group.KEYS, K.SCROLL_LOCK),
    CAPS_LOCK("caps_lock", "Caps Lock (клавиша)", "Caps", Group.KEYS, K.CAPS_LOCK),
    NUM_LOCK("num_lock", "Num Lock", "Num", Group.KEYS, K.NUM_LOCK),

    F1("f1", "F1", "F1", Group.FKEYS, K.F1),
    F2("f2", "F2", "F2", Group.FKEYS, K.F1 + 1),
    F3("f3", "F3", "F3", Group.FKEYS, K.F1 + 2),
    F4("f4", "F4", "F4", Group.FKEYS, K.F1 + 3),
    F5("f5", "F5", "F5", Group.FKEYS, K.F1 + 4),
    F6("f6", "F6", "F6", Group.FKEYS, K.F1 + 5),
    F7("f7", "F7", "F7", Group.FKEYS, K.F1 + 6),
    F8("f8", "F8", "F8", Group.FKEYS, K.F1 + 7),
    F9("f9", "F9", "F9", Group.FKEYS, K.F1 + 8),
    F10("f10", "F10", "F10", Group.FKEYS, K.F1 + 9),
    F11("f11", "F11", "F11", Group.FKEYS, K.F1 + 10),
    F12("f12", "F12", "F12", Group.FKEYS, K.F1 + 11),

    MEDIA_PLAY_PAUSE("media_play_pause", "Воспроизведение / пауза", "⏯", Group.MEDIA, K.MEDIA_PLAY_PAUSE),
    MEDIA_PREVIOUS("media_previous", "Предыдущий трек (держать — перемотка назад)", "⏮", Group.MEDIA, K.MEDIA_PREVIOUS),
    MEDIA_NEXT("media_next", "Следующий трек (держать — перемотка вперёд)", "⏭", Group.MEDIA, K.MEDIA_NEXT),
    MEDIA_STOP("media_stop", "Стоп", "⏹", Group.MEDIA, K.MEDIA_STOP),
    MEDIA_REWIND("media_rewind", "Перемотка назад", "⏪", Group.MEDIA, K.MEDIA_REWIND),
    MEDIA_FAST_FORWARD("media_fast_forward", "Перемотка вперёд", "⏩", Group.MEDIA, K.MEDIA_FAST_FORWARD),
    MEDIA_RECORD("media_record", "Запись", "⏺", Group.MEDIA, K.MEDIA_RECORD),
    VOLUME_UP("volume_up", "Громкость +", "🔊", Group.MEDIA),
    VOLUME_DOWN("volume_down", "Громкость −", "🔉", Group.MEDIA),
    VOLUME_MUTE("volume_mute", "Звук вкл/выкл", "🔇", Group.MEDIA),

    SWITCH_LAYOUT("switch_layout", "Сменить раскладку", "🌐", Group.CLACK),
    OPEN_SYMBOLS("open_symbols", "Открыть панель символов", "#?", Group.CLACK),
    OPEN_EMOJI("open_emoji", "Открыть эмодзи", "😀", Group.CLACK),
    OPEN_SETTINGS("open_settings", "Настройки Clack", "⚙", Group.CLACK);

    enum class Group(val title: String) {
        NONE(""), NAVIGATION("Навигация"), EDIT("Правка"), KEYS("Клавиши"), FKEYS("F-клавиши"),
        MEDIA("Медиа"), CLACK("Clack")
    }

    /** Moves the cursor: with Shift it should extend the selection. */
    val isMovement: Boolean get() = group == Group.NAVIGATION
    val isMedia: Boolean get() = group == Group.MEDIA && key != 0
    val isVolume: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN || this == VOLUME_MUTE
    /** Actions the keyboard does itself instead of sending a key. */
    val isInternal: Boolean get() = group == Group.CLACK || this == DELETE_WORD
    /** Clipboard actions are tried through the editor's context menu first (more reliable than Ctrl+key). */
    val isClipboard: Boolean get() = this == COPY || this == CUT || this == PASTE || this == SELECT_ALL
    /** Tap = this action, hold = seek. */
    val seekKey: Int? get() = when (this) {
        MEDIA_PREVIOUS -> K.MEDIA_REWIND
        MEDIA_NEXT -> K.MEDIA_FAST_FORWARD
        else -> null
    }

    companion object {
        fun byId(id: String): SymPadAction? = values().firstOrNull { it.id == id }
    }
}

/** android.view.KeyEvent key codes used by SymPad. */
object K {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val A = 29
    const val C = 31
    const val F = 34
    const val S = 47
    const val V = 50
    const val X = 52
    const val Z = 54
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val CAPS_LOCK = 115
    const val SCROLL_LOCK = 116
    const val SYSRQ = 120
    const val BREAK = 121
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val MEDIA_RECORD = 130
    const val F1 = 131
    const val NUM_LOCK = 143
}

/** Which physical letter key does what. Stored in the same {"mappings": {...}} JSON as the other key maps. */
data class SymPadMap(val actions: Map<String, SymPadAction>) {
    operator fun get(keyName: String): SymPadAction = actions[keyName] ?: SymPadAction.NONE

    fun with(keyName: String, action: SymPadAction): SymPadMap =
        SymPadMap(LinkedHashMap(actions).apply { if (action == SymPadAction.NONE) remove(keyName) else put(keyName, action) })

    fun toJson(): String = LayoutEditing.keyMapToJson(
        LayoutEditing.TITAN2_ROWS.flatten().filter { it in actions }.associateWith { actions.getValue(it).id },
        "SymPad"
    )

    companion object {
        /**
         * Default scheme: two 3×3 pads.
         *   left  (cursor):  Q ⇠word  W ↑  E word⇢ / A ←  S ·  D → / Z Home  X ↓  C End
         *   right (pages, media): U doc start  I PgUp  O doc end / J ⏮  K ⏯  L ⏭ / N PgDn
         * The middle keys (R T F G V B) and S stay free for later.
         */
        val DEFAULT = SymPadMap(linkedMapOf(
            "KEYCODE_Q" to SymPadAction.WORD_LEFT,
            "KEYCODE_W" to SymPadAction.UP,
            "KEYCODE_E" to SymPadAction.WORD_RIGHT,
            "KEYCODE_A" to SymPadAction.LEFT,
            "KEYCODE_D" to SymPadAction.RIGHT,
            "KEYCODE_Z" to SymPadAction.LINE_START,
            "KEYCODE_X" to SymPadAction.DOWN,
            "KEYCODE_C" to SymPadAction.LINE_END,
            "KEYCODE_U" to SymPadAction.DOC_START,
            "KEYCODE_I" to SymPadAction.PAGE_UP,
            "KEYCODE_O" to SymPadAction.DOC_END,
            "KEYCODE_J" to SymPadAction.MEDIA_PREVIOUS,
            "KEYCODE_K" to SymPadAction.MEDIA_PLAY_PAUSE,
            "KEYCODE_L" to SymPadAction.MEDIA_NEXT,
            "KEYCODE_N" to SymPadAction.PAGE_DOWN
        ))

        /** Unknown action ids are skipped, so a file from a newer version still loads. */
        fun parse(json: String): SymPadMap {
            val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Схема SymPad должна быть JSON-объектом")
            val m = root["mappings"] as? Map<*, *> ?: throw IllegalArgumentException("В схеме нет \"mappings\"")
            val out = LinkedHashMap<String, SymPadAction>()
            for ((k, v) in m) {
                val key = k as? String ?: continue
                val action = (v as? String)?.let { SymPadAction.byId(it) } ?: continue
                if (key.startsWith("KEYCODE_") && action != SymPadAction.NONE) out[key] = action
            }
            return SymPadMap(out)
        }
    }
}

/**
 * Tells a SYM tap from a long press and from SYM used as a modifier (held while other keys go).
 * Times are event times in ms.
 */
class SymKeyTracker(var longPressMs: Long = 500) {
    enum class Release { TAP, CHORD, LONG }

    var held = false
        private set
    private var downAt = 0L
    private var chorded = false
    private var longFired = false

    fun onDown(now: Long) {
        if (held) return
        held = true
        downAt = now
        chorded = false
        longFired = false
    }

    /** Another key went down while SYM was held: SYM is a modifier this time. */
    fun onOtherKey() { if (held) chorded = true }

    /** Call from a timer at downAt + longPressMs; true if this counts as a long press right now. */
    fun checkLong(now: Long): Boolean {
        if (!held || chorded || longFired || now - downAt < longPressMs) return false
        longFired = true
        return true
    }

    fun onUp(now: Long): Release {
        held = false
        return when {
            chorded -> Release.CHORD
            longFired || now - downAt >= longPressMs -> Release.LONG
            else -> Release.TAP
        }
    }

    fun reset() { held = false; chorded = false; longFired = false }
}
