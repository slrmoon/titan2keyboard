package clack.core.predict

/** Splits free text into sentences of normalized words, for learning from existing messages. */
object Tokenizer {
    fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’'

    /** Lowercase, ё kept as is, curly apostrophe normalized. */
    fun normalize(word: String): String = word.lowercase().replace('’', '\'')

    fun sentences(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                val w = word.toString().trim('-', '\'')
                if (w.isNotEmpty()) current.add(normalize(w))
                word.setLength(0)
            }
        }
        fun flushSentence() {
            flushWord()
            if (current.isNotEmpty()) out.add(current)
            current = ArrayList()
        }
        // Work token by token (split on whitespace) so links, handles and numbers can be dropped whole.
        val tokens = Regex("\\s+|(?<=\\n)|(?=\\n)").split(text)
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (token == "\n") { flushSentence(); continue }
            if (isJunk(token)) {
                // breaks the chain of words: what comes after is not a continuation of what came before
                flushSentence()
                continue
            }
            for (i in token.indices) {
                val c = token[i]
                when {
                    isWordChar(c) -> word.append(c)
                    c == '-' && word.isNotEmpty() && i + 1 < token.length && token[i + 1].isLetter() -> word.append(c)
                    c == '.' || c == '!' || c == '?' || c == '…' -> flushSentence()
                    else -> flushWord()
                }
            }
            flushWord()
        }
        flushSentence()
        return out
    }

    private fun isJunk(token: String): Boolean =
        token.any { it.isDigit() } || "://" in token || token.startsWith("www.") ||
            '@' in token || token.startsWith("#") || token.startsWith("/")

    /** Feed a block of text into [model]. Returns the number of words learned. */
    fun learnText(model: UserModel, text: String, maxWordLength: Int = 32): Int {
        var n = 0
        for (s in sentences(text)) {
            val ctx = ArrayList<String>()
            for (w in s) {
                if (w.length > maxWordLength) { ctx.clear(); continue }
                model.learn(ctx.takeLast(2), w)
                ctx.add(w)
                n++
            }
        }
        return n
    }
}
