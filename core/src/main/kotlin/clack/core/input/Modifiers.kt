package clack.core.input

/**
 * Sticky modifier like on BlackBerry-style keyboards:
 * tap once → applies to the next character (one-shot), tap twice quickly → locked,
 * tap again → off. Holding the modifier while pressing another key works as a
 * normal chord and does not change the sticky state.
 */
class StickyModifier(private val doubleTapMs: Long = 400) {
    enum class Mode { OFF, ONESHOT, LOCKED }

    var mode: Mode = Mode.OFF
        private set
    var held: Boolean = false
        private set

    private var chorded = false
    private var lastTapUpAt = Long.MIN_VALUE / 2

    val active: Boolean get() = held || mode != Mode.OFF

    fun onDown() {
        if (!held) {
            held = true
            chorded = false
        }
    }

    /** Another key went down while this modifier is held. */
    fun onOtherKeyWhileHeld() {
        if (held) chorded = true
    }

    fun onUp(now: Long) {
        if (!held) return
        held = false
        if (chorded) return
        mode = when (mode) {
            Mode.OFF -> Mode.ONESHOT
            Mode.ONESHOT -> if (now - lastTapUpAt <= doubleTapMs) Mode.LOCKED else Mode.OFF
            Mode.LOCKED -> Mode.OFF
        }
        lastTapUpAt = now
    }

    /** Called after a character was produced with this modifier active. */
    fun consumeOneShot() {
        if (mode == Mode.ONESHOT) mode = Mode.OFF
    }

    fun reset() {
        mode = Mode.OFF
        held = false
        chorded = false
    }
}

/** Modifiers that can take part in a shortcut. */
enum class Mod { SHIFT, ALT, CTRL, FN, META }

/** A key combination such as Fn+Space. [keyName] is an Android key code name, e.g. "KEYCODE_SPACE". */
data class Shortcut(val mods: Set<Mod>, val keyName: String) {
    fun matches(heldMods: Set<Mod>, key: String): Boolean = key == keyName && heldMods == mods

    fun serialize(): String = mods.sortedBy { it.ordinal }.joinToString("+") { it.name } + "|" + keyName

    fun label(): String {
        val keyLabel = keyName.removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
        val modLabel = mods.sortedBy { it.ordinal }.joinToString("+") {
            when (it) {
                Mod.SHIFT -> "Shift"; Mod.ALT -> "Alt"; Mod.CTRL -> "Ctrl"; Mod.FN -> "Fn"; Mod.META -> "Meta"
            }
        }
        return if (modLabel.isEmpty()) keyLabel else "$modLabel+$keyLabel"
    }

    companion object {
        /** Ctrl+Space; Fn+Space works too on devices that report a real Fn key. */
        val DEFAULTS = listOf(Shortcut(setOf(Mod.CTRL), "KEYCODE_SPACE"), Shortcut(setOf(Mod.FN), "KEYCODE_SPACE"))

        fun parse(s: String): Shortcut? {
            val parts = s.split('|')
            if (parts.size != 2 || parts[1].isBlank()) return null
            val mods = if (parts[0].isBlank()) emptySet() else parts[0].split('+').mapNotNull { name ->
                Mod.values().firstOrNull { it.name == name }
            }.toSet()
            return Shortcut(mods, parts[1])
        }
    }
}

/**
 * Multi-tap tracking: pressing the same key again within [timeoutMs] cycles through its outputs.
 */
class MultiTapTracker(var timeoutMs: Long = 450) {
    private var lastKey: String? = null
    private var lastAt = 0L
    private var index = 0

    data class Result(val index: Int, val replacesPrevious: Boolean)

    fun onKey(keyName: String, outputs: Int, now: Long): Result {
        if (outputs > 1 && keyName == lastKey && now - lastAt <= timeoutMs) {
            index = (index + 1) % outputs
            lastAt = now
            return Result(index, replacesPrevious = true)
        }
        lastKey = keyName
        lastAt = now
        index = 0
        return Result(0, replacesPrevious = false)
    }

    fun reset() {
        lastKey = null
        index = 0
    }
}
