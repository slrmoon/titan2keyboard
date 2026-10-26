package clack.core

import clack.core.launch.AppEntry
import clack.core.launch.AppFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFilterTest {
    private val apps = listOf("Telegram", "Еда", "Termux", "Google Maps", "Gmail", "Ёлка", "Сбербанк", "Calculator", "Камера")
        .map { AppEntry(it, "pkg." + it.lowercase(), "Main") }

    @Test fun keyMatchesLettersOfAllLayouts() {
        val layouts = listOf(TestData.layout("russian_jcuken"), TestData.layout("qwerty"))
        assertEquals(setOf("t", "е", "ё"), AppFilter.lettersForKey("KEYCODE_T", layouts))
        val found = AppFilter.startingWith(apps, AppFilter.lettersForKey("KEYCODE_T", layouts)).map { it.label }
        assertEquals(listOf("Telegram", "Termux", "Еда", "Ёлка"), found)
    }

    @Test fun keyCWithRussianLayoutFindsBothScripts() {
        val layouts = listOf(TestData.layout("russian_jcuken"), TestData.layout("qwerty"))
        // C key: "c" in QWERTY, "с" in ЙЦУКЕН
        val found = AppFilter.startingWith(apps, AppFilter.lettersForKey("KEYCODE_C", layouts)).map { it.label }
        assertEquals(listOf("Calculator", "Сбербанк"), found)
    }

    @Test fun searchNarrowsByWordStarts() {
        assertEquals(listOf("Google Maps"), AppFilter.search(apps, "go ma").map { it.label })
        assertEquals(listOf("Gmail", "Google Maps"), AppFilter.search(apps, "g").map { it.label })
        assertEquals(listOf("Telegram"), AppFilter.search(apps, "gram").map { it.label })
    }
}
