package clack.core.dict

import java.util.PriorityQueue

/**
 * Compact array-based trie over lowercase words with a frequency per word.
 * ~14 bytes per node, so a 100k-word dictionary stays at a few megabytes.
 */
class Trie private constructor(
    private val chars: CharArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    private val wordFreq: FloatArray,   // NaN when the node does not end a word
    private val subtreeMax: FloatArray, // best frequency anywhere below (inclusive)
    val size: Int
) {
    val nodeCount: Int get() = chars.size

    fun frequency(word: String): Float? {
        var node = ROOT
        for (c in word) {
            node = child(node, c)
            if (node < 0) return null
        }
        val f = wordFreq[node]
        return if (f.isNaN()) null else f
    }

    fun contains(word: String) = frequency(word) != null

    internal fun child(node: Int, c: Char): Int {
        var ch = firstChild[node]
        while (ch >= 0) {
            if (chars[ch] == c) return ch
            ch = nextSibling[ch]
        }
        return -1
    }

    /** Node reached by following [prefix] exactly, or -1. */
    fun nodeFor(prefix: String): Int {
        var node = ROOT
        for (c in prefix) {
            node = child(node, c)
            if (node < 0) return -1
        }
        return node
    }

    /** Most frequent words below [node] (word = [prefix] + suffix), best first. */
    fun topWordsBelow(node: Int, prefix: String, limit: Int): List<Pair<String, Float>> {
        if (node < 0 || limit <= 0) return emptyList()
        data class Item(val node: Int, val text: String, val bound: Float, val isWord: Boolean)
        val pq = PriorityQueue<Item>(compareByDescending { it.bound })
        pq.add(Item(node, prefix, subtreeMax[node], false))
        val out = ArrayList<Pair<String, Float>>(limit)
        while (pq.isNotEmpty() && out.size < limit) {
            val it = pq.poll()
            if (it.isWord) {
                out.add(it.text to it.bound)
                continue
            }
            val f = wordFreq[it.node]
            if (!f.isNaN()) pq.add(Item(it.node, it.text, f, true))
            var ch = firstChild[it.node]
            while (ch >= 0) {
                pq.add(Item(ch, it.text + chars[ch], subtreeMax[ch], false))
                ch = nextSibling[ch]
            }
        }
        return out
    }

    /**
     * Error-tolerant search. Walks the trie computing a weighted Damerau-Levenshtein row
     * per node (costs from [costs]); branches whose best cost exceeds the budget are cut.
     *
     * @param onWord called for complete words within [maxCost].
     * @param onPrefix called for nodes whose path matches the whole input as a prefix
     *   within [maxPrefixCost] (for completions with typos), depth ≥ input length.
     */
    fun fuzzySearch(
        input: String,
        costs: EditCosts,
        maxCost: Float,
        maxPrefixCost: Float,
        onWord: (word: String, cost: Float, freq: Float) -> Unit,
        onPrefix: (node: Int, path: String, cost: Float) -> Unit
    ) {
        val n = input.length
        val budget = maxOf(maxCost, maxPrefixCost)
        val maxDepth = n + 4
        val rows = Array(maxDepth + 1) { FloatArray(n + 1) }
        val path = CharArray(maxDepth + 1)
        // Row for the empty intended prefix: every typed char is an extra insertion.
        rows[0][0] = 0f
        for (j in 1..n) rows[0][j] = rows[0][j - 1] + costs.insertion(input[j - 1], null)

        fun visit(node: Int, depth: Int) {
            val c = chars[node]
            path[depth - 1] = c
            val prev = rows[depth - 1]
            val row = rows[depth]
            row[0] = prev[0] + costs.deletion()
            var rowMin = row[0]
            for (j in 1..n) {
                val t = input[j - 1]
                var best = prev[j - 1] + costs.substitution(c, t)
                val ins = row[j - 1] + costs.insertion(t, c)
                if (ins < best) best = ins
                val del = prev[j] + costs.deletion()
                if (del < best) best = del
                if (depth >= 2 && j >= 2 && c == input[j - 2] && path[depth - 2] == t && c != t) {
                    val tr = rows[depth - 2][j - 2] + costs.transposition()
                    if (tr < best) best = tr
                }
                row[j] = best
                if (best < rowMin) rowMin = best
            }
            val full = row[n]
            val f = wordFreq[node]
            if (!f.isNaN() && full <= maxCost) onWord(String(path, 0, depth), full, f)
            if (depth >= n && full <= maxPrefixCost) onPrefix(node, String(path, 0, depth), full)
            if (rowMin > budget || depth >= maxDepth) return
            var ch = firstChild[node]
            while (ch >= 0) {
                visit(ch, depth + 1)
                ch = nextSibling[ch]
            }
        }

        var ch = firstChild[ROOT]
        while (ch >= 0) {
            visit(ch, 1)
            ch = nextSibling[ch]
        }
    }

    interface EditCosts {
        fun substitution(intended: Char, typed: Char): Float
        fun insertion(extra: Char, neighbor: Char?): Float
        fun deletion(): Float
        fun transposition(): Float
    }

    companion object {
        private const val ROOT = 0

        fun build(entries: Map<String, Float>): Trie {
            var cap = 1024
            var chars = CharArray(cap)
            var first = IntArray(cap) { -1 }
            var next = IntArray(cap) { -1 }
            var freq = FloatArray(cap) { Float.NaN }
            var count = 1 // root
            fun grow() {
                cap *= 2
                chars = chars.copyOf(cap)
                first = first.copyOf(cap).also { a -> for (i in count until cap) a[i] = -1 }
                next = next.copyOf(cap).also { a -> for (i in count until cap) a[i] = -1 }
                freq = freq.copyOf(cap).also { a -> for (i in count until cap) a[i] = Float.NaN }
            }
            var words = 0
            for ((word, f) in entries) {
                if (word.isEmpty()) continue
                var node = ROOT
                for (c in word) {
                    var ch = first[node]
                    var found = -1
                    while (ch >= 0) {
                        if (chars[ch] == c) { found = ch; break }
                        ch = next[ch]
                    }
                    if (found < 0) {
                        if (count == cap) grow()
                        found = count++
                        chars[found] = c
                        next[found] = first[node]
                        first[node] = found
                    }
                    node = found
                }
                if (freq[node].isNaN()) words++
                if (freq[node].isNaN() || f > freq[node]) freq[node] = f
            }
            chars = chars.copyOf(count)
            first = first.copyOf(count)
            next = next.copyOf(count)
            freq = freq.copyOf(count)
            // Children are always created after their parent, so a reverse sweep fills subtree maxima.
            val sub = FloatArray(count) { if (freq[it].isNaN()) Float.NEGATIVE_INFINITY else freq[it] }
            val parentOf = IntArray(count) { -1 }
            for (p in 0 until count) {
                var ch = first[p]
                while (ch >= 0) { parentOf[ch] = p; ch = next[ch] }
            }
            for (i in count - 1 downTo 1) {
                val p = parentOf[i]
                if (sub[i] > sub[p]) sub[p] = sub[i]
            }
            return Trie(chars, first, next, freq, sub, words)
        }
    }
}
