package clack.core

import clack.core.input.Mod
import clack.core.input.MultiTapTracker
import clack.core.input.Shortcut
import clack.core.input.StickyModifier
import clack.core.layout.KeyboardLayout
import clack.core.predict.Tokenizer
import clack.core.predict.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

class InputLogicTest {
    @Test fun parsesPastieraLayoutWithMultiTap() {
        val json = """
            {"name":"Test","description":"d","mappings":{
              "KEYCODE_Q":{"lowercase":"й","uppercase":"Й"},
              "KEYCODE_P":{"lowercase":"з","uppercase":"З","multiTapEnabled":true,
                 "taps":[{"lowercase":"з","uppercase":"З"},{"lowercase":"х","uppercase":"Х"}]}}}
        """.trimIndent()
        val l = KeyboardLayout.parse("t", json)
        assertEquals("Test", l.name)
        assertEquals("й", l.mappings["KEYCODE_Q"]!!.primary.lower)
        assertEquals(listOf("з", "х"), l.mappings["KEYCODE_P"]!!.outputs.map { it.lower })
        assertEquals("ru", l.language)
    }

    @Test fun bundledLayoutsLoad() {
        val ru = TestData.layout("russian_jcuken")
        val en = TestData.layout("qwerty")
        assertEquals(26, ru.mappings.size)
        assertEquals("ru", ru.language)
        assertEquals("en", en.language)
        assertEquals(listOf("ь", "б", "ю"), ru.mappings["KEYCODE_M"]!!.outputs.map { it.lower })
        assertEquals("0", TestData.altLayer()["KEYCODE_Q"])
        // every Russian letter is reachable
        val letters = ru.mappings.values.flatMap { m -> m.outputs.map { it.lower } }.toSet()
        for (c in "абвгдеёжзийклмнопрстуфхцчшщъыьэюя") assertTrue("missing $c", c.toString() in letters)
    }

    @Test fun stickyModifierCycles() {
        val m = StickyModifier(doubleTapMs = 400)
        m.onDown(); m.onUp(0)
        assertEquals(StickyModifier.Mode.ONESHOT, m.mode)
        m.consumeOneShot()
        assertEquals(StickyModifier.Mode.OFF, m.mode)

        m.onDown(); m.onUp(1000); m.onDown(); m.onUp(1200)
        assertEquals(StickyModifier.Mode.LOCKED, m.mode)
        m.consumeOneShot()
        assertEquals(StickyModifier.Mode.LOCKED, m.mode)
        m.onDown(); m.onUp(3000)
        assertEquals(StickyModifier.Mode.OFF, m.mode)

        // held as a chord: no sticky state afterwards
        m.onDown(); assertTrue(m.active); m.onOtherKeyWhileHeld(); m.onUp(5000)
        assertEquals(StickyModifier.Mode.OFF, m.mode)
        assertFalse(m.active)
    }

    @Test fun shortcutRoundTrip() {
        val s = Shortcut(setOf(Mod.CTRL, Mod.SHIFT), "KEYCODE_SPACE")
        assertEquals(s, Shortcut.parse(s.serialize()))
        assertEquals("Shift+Ctrl+Space", s.label())
        assertTrue(Shortcut.DEFAULTS.any { it.matches(setOf(Mod.FN), "KEYCODE_SPACE") })
        assertFalse(s.matches(setOf(Mod.CTRL), "KEYCODE_SPACE"))
    }

    @Test fun multiTapCyclesWithinTimeout() {
        val t = MultiTapTracker(timeoutMs = 400)
        assertEquals(MultiTapTracker.Result(0, false), t.onKey("P", 3, 0))
        assertEquals(MultiTapTracker.Result(1, true), t.onKey("P", 3, 200))
        assertEquals(MultiTapTracker.Result(2, true), t.onKey("P", 3, 400))
        assertEquals(MultiTapTracker.Result(0, true), t.onKey("P", 3, 600))
        assertEquals(MultiTapTracker.Result(0, false), t.onKey("P", 3, 2000))
        assertEquals(MultiTapTracker.Result(0, false), t.onKey("Q", 1, 2100))
    }

    @Test fun tokenizerSplitsSentencesAndSkipsJunk() {
        val s = Tokenizer.sentences("Привет, как дела? Смотри https://x.ru/abc ну кто-то 123 пришёл.\nОк")
        assertEquals(listOf(listOf("привет", "как", "дела"), listOf("смотри"), listOf("ну", "кто-то"), listOf("пришёл"), listOf("ок")), s)
    }

    @Test fun userModelPersists() {
        val m = UserModel()
        Tokenizer.learnText(m, "доброе утро. доброе утро")
        val w = StringWriter(); m.write(w)
        val m2 = UserModel(); m2.read(BufferedReader(StringReader(w.toString())))
        assertEquals(2, m2.count("утро"))
        assertEquals(2, m2.bigram("доброе", "утро"))
        assertEquals("утро", m2.next(listOf("доброе"), 1).first().first)
    }
}
