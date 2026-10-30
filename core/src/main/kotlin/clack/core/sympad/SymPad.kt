package clack.core.sympad

import clack.core.Json
import clack.core.layout.LayoutEditing

/**
 * SymPad: letter keys used together with SYM as navigation, editing and media keys.
 *
 * Each action describes the Android key it emulates. Android key codes are repeated here as
 * constants so the core stays free of Android classes (values from android.view.KeyEvent).
 */
enum class SymPadAction(val id: String, val label: String, val short: String, val group: Group, val key: Int = 0, val ctrl: Boolean = false) {
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

    SELECT("select", "Выбрать / OK (центр крестовины)", "OK", Group.KEYS, K.DPAD_CENTER),
    TAB("tab", "Tab", "Tab", Group.KEYS, K.TAB),
    ESCAPE("escape", "Esc", "Esc", Group.KEYS, K.ESCAPE),
    DELETE_FORWARD("delete_forward", "Удалить справа (Del)", "Del", Group.KEYS, K.FORWARD_DEL),

    MEDIA_PLAY_PAUSE("media_play_pause", "Воспроизведение / пауза", "⏯", Group.MEDIA, K.MEDIA_PLAY_PAUSE),
    MEDIA_PREVIOUS("media_previous", "Предыдущий трек (держать — перемотка назад)", "⏮", Group.MEDIA, K.MEDIA_PREVIOUS),
    MEDIA_NEXT("media_next", "Следующий трек (держать — перемотка вперёд)", "⏭", Group.MEDIA, K.MEDIA_NEXT),
    MEDIA_STOP("media_stop", "Стоп", "⏹", Group.MEDIA, K.MEDIA_STOP),
    VOLUME_UP("volume_up", "Громкость +", "🔊", Group.MEDIA),
    VOLUME_DOWN("volume_down", "Громкость −", "🔉", Group.MEDIA),
    VOLUME_MUTE("volume_mute", "Звук вкл/выкл", "🔇", Group.MEDIA);

    enum class Group(val title: String) {
        NONE(""), NAVIGATION("Навигация"), KEYS("Клавиши"), MEDIA("Медиа")
    }

    /** Moves the cursor: with Shift it should extend the selection. */
    val isMovement: Boolean get() = group == Group.NAVIGATION
    val isMedia: Boolean get() = key in MEDIA_KEYS
    val isVolume: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN || this == VOLUME_MUTE
    /** Tap = this action, hold = seek. */
    val seekKey: Int? get() = when (this) {
        MEDIA_PREVIOUS -> K.MEDIA_REWIND
        MEDIA_NEXT -> K.MEDIA_FAST_FORWARD
        else -> null
    }

    companion object {
        private val MEDIA_KEYS = setOf(K.MEDIA_PLAY_PAUSE, K.MEDIA_PREVIOUS, K.MEDIA_NEXT, K.MEDIA_STOP)
        fun byId(id: String): SymPadAction? = values().firstOrNull { it.id == id }
    }
}

/** android.view.KeyEvent key codes used by SymPad. */
object K {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val TAB = 61
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
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
