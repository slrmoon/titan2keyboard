package clack.core

import clack.core.dict.Trie
import clack.core.predict.Tokenizer
import clack.core.predict.UserModel
import kotlin.math.ln

enum class CandidateKind { TYPED, CORRECTION, COMPLETION, NEXT_WORD }

data class Candidate(
    /** Lowercase dictionary form; apply casing with [applyCasing] before inserting. */
    val word: String,
    val kind: CandidateKind,
    val score: Double,
    val cost: Float = 0f
)

data class Suggestions(
    val items: List<Candidate>,
    /** Replace the typed word with this when the user presses space/punctuation. */
    val autoCorrect: Candidate?,
    /** The typed word itself, when the dictionary does not know it (offered so it can be kept). */
    val unknownTyped: String?
) {
    companion object { val EMPTY = Suggestions(emptyList(), null, null) }
}

/**
 * Combines the bundled dictionary, the user's own vocabulary and n-grams,
 * and a keyboard-aware typo model into the three suggestions shown in the bar.
 */
class SuggestionEngine(
    private val main: Trie,
    private val user: UserModel,
    var costs: Trie.EditCosts
) {
    @Volatile private var userTrie: Trie = Trie.build(emptyMap())
    private var userTrieVersion = -1L

    /**
     * Rebuild the index over learned words the dictionary does not have (slang, names).
     * Only runs when the user's vocabulary actually changed; call off the UI thread.
     */
    @Synchronized fun refreshUserIndex() {
        val v = user.vocabularyVersion
        if (v == userTrieVersion) return
        val own = user.unigramSnapshot().filterKeys { !main.contains(it) }
        userTrie = Trie.build(own.mapValues { (_, c) -> userFrequency(c) })
        userTrieVersion = v
    }

    fun isKnown(word: String): Boolean {
        val w = Tokenizer.normalize(word)
        val f = main.frequency(w)
        return (f != null && f >= MIN_KNOWN_ZIPF) || user.count(w) >= UserModel.KNOWN_THRESHOLD
    }

    /** Suggestions for the word currently being typed. [context] = previous words of the sentence, normalized. */
    fun suggest(context: List<String>, typedRaw: String, limit: Int = 3): Suggestions {
        val typed = Tokenizer.normalize(typedRaw)
        if (typed.isEmpty() || typed.any { !Tokenizer.isWordChar(it) && it != '-' }) return Suggestions.EMPTY
        val n = typed.length
        val maxCost = when {
            n <= 2 -> 0.5f
            n == 3 -> 1.0f
            n <= 5 -> 1.4f
            else -> 1.9f
        }
        val maxPrefixCost = when {
            n <= 2 -> 0f
            n == 3 -> 0.5f
            n <= 5 -> 0.95f
            else -> 1.4f
        }
        val best = HashMap<String, Candidate>()
        fun offer(c: Candidate) {
            val old = best[c.word]
            if (old == null || c.score > old.score) best[c.word] = c
        }
        val ctxBoost = ContextBoost(context)
        val userIndex = userTrie

        for (trie in listOf(main, userIndex)) {
            val prefixNodes = ArrayList<Triple<Int, String, Float>>()
            trie.fuzzySearch(typed, costs, maxCost, maxPrefixCost,
                onWord = { w, cost, _ ->
                    offer(Candidate(w, if (w == typed) CandidateKind.TYPED else CandidateKind.CORRECTION,
                        wordScore(w) - COST_WEIGHT * cost + ctxBoost(w), cost))
                },
                onPrefix = { node, path, cost -> prefixNodes.add(Triple(node, path, cost)) })
            // Completions: frequent words that continue the typed prefix (allowing the prefix to have typos).
            prefixNodes.sortBy { it.third }
            for ((node, path, cost) in prefixNodes.take(12)) {
                for ((w, _) in trie.topWordsBelow(node, path, 3)) {
                    if (w.length <= n) continue
                    val freq = wordScore(w)
                    if (freq < MIN_COMPLETION_ZIPF && user.count(w) == 0) continue
                    val extra = (w.length - n).coerceAtMost(8)
                    offer(Candidate(w, CandidateKind.COMPLETION,
                        freq - COST_WEIGHT * cost - COMPLETION_LENGTH_PENALTY * extra - COMPLETION_PENALTY + ctxBoost(w), cost))
                }
            }
        }

        val ranked = best.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        // Common misspellings are removed from the bundled lists at build time
        // (tools/build_dictionary.py), so "known" can be trusted here.
        val typedKnown = isKnown(typed)

        // Auto-correction: only when the typed word is unknown and one fix clearly wins.
        var auto: Candidate? = null
        if (!typedKnown && n >= 2) {
            val fixes = ranked.filter { it.kind == CandidateKind.CORRECTION }
            val top = fixes.firstOrNull()
            if (top != null) {
                val runnerUp = fixes.getOrNull(1)
                val margin = if (runnerUp == null) Double.MAX_VALUE else top.score - runnerUp.score
                if (margin >= AUTOCORRECT_MARGIN || top.cost <= TypoCostThresholds.SURE) auto = top
            }
        }

        val items = ArrayList<Candidate>(limit)
        if (auto != null) items.add(auto)
        for (c in ranked) {
            if (items.size >= limit) break
            if (c.word == auto?.word) continue
            if (c.kind == CandidateKind.TYPED) continue // typed word is shown separately when unknown
            items.add(c)
        }
        // When the typed word is a real word, show it first so it can be confirmed.
        if (typedKnown) {
            items.removeAll { it.word == typed }
            items.add(0, Candidate(typed, CandidateKind.TYPED, Double.MAX_VALUE))
            while (items.size > limit) items.removeAt(items.size - 1)
        }
        return Suggestions(items, auto, if (typedKnown) null else typed)
    }

    /** Predictions for the next word, after a space. */
    fun predictNext(context: List<String>, limit: Int = 3): List<Candidate> =
        user.next(context, limit).map { (w, c) -> Candidate(w, CandidateKind.NEXT_WORD, c.toDouble()) }

    private fun wordScore(w: String): Double {
        val base = main.frequency(w)?.toDouble() ?: 0.0
        val c = user.count(w)
        val personal = if (c > 0) userFrequency(c).toDouble() else 0.0
        return maxOf(base, personal) + if (c > 0) minOf(1.5, 0.4 * ln(1.0 + c)) else 0.0
    }

    private inner class ContextBoost(context: List<String>) {
        private val prev1 = context.lastOrNull() ?: UserModel.SENTENCE_START
        private val prev2 = if (context.size >= 2) context[context.size - 2] else UserModel.SENTENCE_START
        operator fun invoke(w: String): Double {
            val b = user.bigram(prev1, w)
            val t = user.trigram(prev2, prev1, w)
            return BIGRAM_WEIGHT * ln(1.0 + b) + TRIGRAM_WEIGHT * ln(1.0 + t)
        }
    }

    private object TypoCostThresholds {
        /** A single neighbor-key slip or multi-tap miscount: correct even without a big margin. */
        const val SURE = 0.31f
    }

    companion object {
        const val COST_WEIGHT = 2.5
        const val BIGRAM_WEIGHT = 0.8
        const val TRIGRAM_WEIGHT = 0.6
        const val COMPLETION_PENALTY = 0.6
        const val COMPLETION_LENGTH_PENALTY = 0.15
        const val MIN_COMPLETION_ZIPF = 2.8
        const val MIN_KNOWN_ZIPF = 1.0f
        const val AUTOCORRECT_MARGIN = 0.7

        /** Map a personal usage count onto the dictionary's zipf scale. */
        fun userFrequency(count: Int): Float = (3.0 + 0.6 * ln(1.0 + count)).toFloat().coerceAtMost(6.5f)

        /** Restore the casing of what the user typed onto a suggestion. */
        fun applyCasing(suggestion: String, typed: String, capitalizeFirst: Boolean = false): String {
            if (suggestion.isEmpty()) return suggestion
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length >= 2 && letters.all { it.isUpperCase() } -> suggestion.uppercase()
                (typed.firstOrNull()?.isUpperCase() == true) || capitalizeFirst ->
                    suggestion.replaceFirstChar { it.uppercaseChar() }
                else -> suggestion
            }
        }
    }
}
