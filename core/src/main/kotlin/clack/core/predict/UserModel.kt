package clack.core.predict

import java.io.BufferedReader
import java.io.Writer

/**
 * What the keyboard has learned from the user, per language:
 * word counts (unigrams) and which words follow which (bigrams, trigrams).
 *
 * Stored as plain text lines so it is easy to inspect, back up or reset:
 *   u<TAB>word<TAB>count
 *   b<TAB>prev<TAB>word<TAB>count
 *   t<TAB>prev2 prev1<TAB>word<TAB>count
 */
class UserModel(
    private val maxUnigrams: Int = 60_000,
    private val maxContexts: Int = 60_000
) {
    private val unigrams = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val trigrams = HashMap<String, HashMap<String, Int>>()

    /** Bumped on every change, so callers can tell when to save or rebuild indexes. */
    @Volatile var version: Long = 0
        private set

    /** Bumped only when a word is seen for the first time (or removed): the vocabulary changed. */
    @Volatile var vocabularyVersion: Long = 0
        private set

    @Synchronized fun count(word: String): Int = unigrams[word] ?: 0
    @Synchronized fun bigram(prev: String, word: String): Int = bigrams[prev]?.get(word) ?: 0
    @Synchronized fun trigram(prev2: String, prev1: String, word: String): Int = trigrams["$prev2 $prev1"]?.get(word) ?: 0
    @Synchronized fun unigramSnapshot(): Map<String, Int> = HashMap(unigrams)
    @Synchronized fun isEmpty(): Boolean = unigrams.isEmpty()

    /**
     * Learn one word in context. [context] is the preceding words in the sentence, oldest first,
     * already normalized; an empty context means the word starts a sentence.
     */
    @Synchronized fun learn(context: List<String>, word: String, weight: Int = 1) {
        if (word.isEmpty()) return
        val old = unigrams[word]
        unigrams[word] = (old ?: 0) + weight
        if (old == null) vocabularyVersion++
        val prev1 = context.lastOrNull() ?: SENTENCE_START
        bump(bigrams, prev1, word, weight)
        if (context.size >= 2) bump(trigrams, context[context.size - 2] + " " + prev1, word, weight)
        else if (context.size == 1) bump(trigrams, "$SENTENCE_START $prev1", word, weight)
        version++
        if (unigrams.size > maxUnigrams * 11 / 10) { prune(); vocabularyVersion++ }
    }

    /** User explicitly kept a word the dictionary did not know: treat it as known from now on. */
    @Synchronized fun addWord(word: String) {
        if (word.isEmpty()) return
        val old = unigrams[word]
        unigrams[word] = maxOf(old ?: 0, KNOWN_THRESHOLD)
        if (old == null) vocabularyVersion++
        version++
    }

    @Synchronized fun forgetWord(word: String) {
        unigrams.remove(word)
        bigrams.values.forEach { it.remove(word) }
        trigrams.values.forEach { it.remove(word) }
        version++; vocabularyVersion++
    }

    @Synchronized fun clear() {
        unigrams.clear(); bigrams.clear(); trigrams.clear()
        version++; vocabularyVersion++
    }

    /** Words that tend to follow [context], best first, with their counts. */
    @Synchronized fun next(context: List<String>, limit: Int): List<Pair<String, Int>> {
        val prev1 = context.lastOrNull() ?: SENTENCE_START
        val prev2 = if (context.size >= 2) context[context.size - 2] else SENTENCE_START
        val scored = HashMap<String, Double>()
        trigrams["$prev2 $prev1"]?.forEach { (w, c) -> scored[w] = (scored[w] ?: 0.0) + c * 3.0 }
        bigrams[prev1]?.forEach { (w, c) -> scored[w] = (scored[w] ?: 0.0) + c }
        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value.toInt() }
    }

    @Synchronized fun write(out: Writer) {
        for ((w, c) in unigrams) out.write("u\t$w\t$c\n")
        for ((p, m) in bigrams) for ((w, c) in m) out.write("b\t$p\t$w\t$c\n")
        for ((p, m) in trigrams) for ((w, c) in m) out.write("t\t$p\t$w\t$c\n")
        out.flush()
    }

    @Synchronized fun read(input: BufferedReader) {
        input.forEachLine { line ->
            val p = line.split('\t')
            when {
                p.size == 3 && p[0] == "u" -> p[2].toIntOrNull()?.let { unigrams[p[1]] = (unigrams[p[1]] ?: 0) + it }
                p.size == 4 && p[0] == "b" -> p[3].toIntOrNull()?.let { bump(bigrams, p[1], p[2], it) }
                p.size == 4 && p[0] == "t" -> p[3].toIntOrNull()?.let { bump(trigrams, p[1], p[2], it) }
            }
        }
        version++; vocabularyVersion++
    }

    private fun bump(map: HashMap<String, HashMap<String, Int>>, ctx: String, word: String, by: Int) {
        val m = map.getOrPut(ctx) { HashMap(4) }
        m[word] = (m[word] ?: 0) + by
    }

    /** Keep the model bounded: drop the rarest words and contexts. */
    private fun prune() {
        if (unigrams.size > maxUnigrams) {
            val keep = unigrams.entries.sortedByDescending { it.value }.take(maxUnigrams).map { it.key }.toHashSet()
            unigrams.keys.retainAll(keep)
        }
        for (map in listOf(bigrams, trigrams)) {
            if (map.size > maxContexts) {
                val keep = map.entries.sortedByDescending { e -> e.value.values.sum() }.take(maxContexts).map { it.key }.toHashSet()
                map.keys.retainAll(keep)
            }
            for (m in map.values) if (m.size > 32) {
                val keep = m.entries.sortedByDescending { it.value }.take(32).map { it.key }.toHashSet()
                m.keys.retainAll(keep)
            }
        }
    }

    companion object {
        const val SENTENCE_START = "<s>"
        /** A word used this many times counts as "known" and is never auto-corrected away. */
        const val KNOWN_THRESHOLD = 2
    }
}
