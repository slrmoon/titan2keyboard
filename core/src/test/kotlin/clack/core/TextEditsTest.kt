package clack.core

import clack.core.input.TextEdits
import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditsTest {
    private fun after(text: String): String = text.dropLast(TextEdits.wordDeleteLength(text))

    @Test fun deletesWordBeforeCursor() {
        assertEquals("Привет, как ", after("Привет, как дел"))
        assertEquals("Привет, как ", after("Привет, как дела "))   // trailing space goes too
        assertEquals("Привет, ", after("Привет, кто-то"))           // hyphenated word is one word
        assertEquals("I ", after("I don't"))
        assertEquals("код ", after("код 2026"))
    }

    @Test fun deletesPunctuationRunWhenNoWord() {
        assertEquals("Привет", after("Привет, "))
        assertEquals("Что", after("Что?!"))
    }

    @Test fun stopsAtLineBreaks() {
        assertEquals("строка\n", after("строка\nслово"))
        assertEquals("строка", after("строка\n"))
        assertEquals("", after(""))
    }
}
