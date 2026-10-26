package clack.core.input

import clack.core.predict.Tokenizer

object TextEdits {
    /**
     * How many characters before the cursor make up "the word" to delete with a double Backspace:
     * trailing spaces, then the word (letters, digits, apostrophes, inner hyphens) — or, when there
     * is no word right there, one run of punctuation/symbols.
     */
    fun wordDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace() && before[i - 1] != '\n') i--
        val afterSpaces = i
        fun isWord(c: Char) = Tokenizer.isWordChar(c) || c.isDigit()
        if (i > 0 && isWord(before[i - 1])) {
            while (i > 0 && (isWord(before[i - 1]) || (before[i - 1] == '-' && i > 1 && isWord(before[i - 2])))) i--
        } else if (i > 0 && before[i - 1] != '\n') {
            while (i > 0 && !isWord(before[i - 1]) && !before[i - 1].isWhitespace()) i--
        }
        // nothing but a line break: delete just that
        if (i == before.length && afterSpaces == before.length && before.isNotEmpty()) return 1
        return before.length - i
    }
}
