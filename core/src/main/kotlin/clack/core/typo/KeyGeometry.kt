package clack.core.typo

import clack.core.dict.Trie
import clack.core.layout.KeyboardLayout
import kotlin.math.hypot

/**
 * Physical position of each letter key, in key widths. Only relative distances matter.
 */
class KeyGeometry(private val positions: Map<String, Pair<Double, Double>>) {
    fun position(keyName: String): Pair<Double, Double>? = positions[keyName]

    fun distance(a: String, b: String): Double? {
        val pa = positions[a] ?: return null
        val pb = positions[b] ?: return null
        return hypot(pa.first - pb.first, pa.second - pb.second)
    }

    companion object {
        private fun row(y: Double, x0: Double, vararg keys: String): List<Pair<String, Pair<Double, Double>>> =
            keys.mapIndexed { i, k -> "KEYCODE_$k" to ((x0 + i) to y) }

        /**
         * Unihertz Titan 2: 10 / 9 / 7 letter keys, the bottom row is split by the space bar:
         *   Q W E R T Y U I O P
         *    A S D F G H J K L
         *     Z X C V [space] B N M
         */
        val TITAN2 = KeyGeometry(
            (row(0.0, 0.0, "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P") +
                row(1.0, 0.5, "A", "S", "D", "F", "G", "H", "J", "K", "L") +
                row(2.0, 1.0, "Z", "X", "C", "V") +
                row(2.0, 6.5, "B", "N", "M")).toMap()
        )
    }
}

/**
 * Cost model for typos, derived from where each letter lives on the physical keyboard
 * under the active layout. Works for any layout (Latin, Cyrillic, ...) because it goes
 * letter → key → position instead of hard-coding QWERTY letters.
 */
class TypoModel(layout: KeyboardLayout, private val geometry: KeyGeometry) : Trie.EditCosts {
    private data class Slot(val keyName: String, val tap: Int)

    /** Every key a letter can come from (a layout may put the same letter on two keys). */
    private val slots: Map<Char, List<Slot>>
    private val cache = HashMap<Long, Float>()

    init {
        val m = HashMap<Char, MutableList<Slot>>()
        for ((keyName, mapping) in layout.mappings) {
            mapping.outputs.forEachIndexed { tap, out ->
                for (s in listOf(out.lower, out.upper)) {
                    if (s.length != 1) continue
                    val list = m.getOrPut(s[0].lowercaseChar()) { ArrayList(1) }
                    if (list.none { it.keyName == keyName }) list.add(Slot(keyName, tap))
                }
            }
        }
        slots = m
    }

    /** Cost of typing [typed] where [intended] was meant. 0 for identical letters. */
    override fun substitution(intended: Char, typed: Char): Float {
        val a = intended.lowercaseChar()
        val b = typed.lowercaseChar()
        if (a == b) return 0f
        if (isYoPair(a, b)) return YO_COST
        val key = (a.code.toLong() shl 32) or b.code.toLong()
        synchronized(cache) { cache[key] }?.let { return it }
        val la = slots[a]
        val lb = slots[b]
        var best = FAR_COST
        if (la != null && lb != null) {
            for (sa in la) for (sb in lb) {
                val c = if (sa.keyName == sb.keyName) SAME_KEY_COST // wrong number of multi-taps
                else {
                    val d = geometry.distance(sa.keyName, sb.keyName)
                    when {
                        d == null -> FAR_COST
                        d <= 1.2 -> NEIGHBOR_COST
                        d <= 2.1 -> NEAR_COST
                        else -> FAR_COST
                    }
                }
                if (c < best) best = c
            }
        }
        synchronized(cache) { cache[key] = best }
        return best
    }

    /** Cost of an extra typed character [extra] next to [neighbor] (the intended letter beside it). */
    override fun insertion(extra: Char, neighbor: Char?): Float {
        if (neighbor == null) return INSERT_COST
        val c = substitution(neighbor, extra)
        return when {
            c == 0f -> DOUBLE_PRESS_COST // same letter pressed twice
            c <= NEIGHBOR_COST -> NEIGHBOR_INSERT_COST // finger touched two keys
            else -> INSERT_COST
        }
    }

    override fun deletion(): Float = DELETE_COST
    override fun transposition(): Float = TRANSPOSE_COST

    private fun isYoPair(a: Char, b: Char) = (a == 'ё' && b == 'е') || (a == 'е' && b == 'ё')

    companion object {
        const val YO_COST = 0.05f
        const val SAME_KEY_COST = 0.3f
        const val NEIGHBOR_COST = 0.45f
        const val NEAR_COST = 0.8f
        const val FAR_COST = 1.0f
        const val DOUBLE_PRESS_COST = 0.45f
        const val NEIGHBOR_INSERT_COST = 0.55f
        const val INSERT_COST = 1.0f
        const val DELETE_COST = 0.9f
        const val TRANSPOSE_COST = 0.6f
    }
}
