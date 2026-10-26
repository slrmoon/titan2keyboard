package clack.core

import clack.core.layout.KeyOutput
import clack.core.layout.KeyboardLayout
import clack.core.layout.LayoutEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutEditingTest {
    @Test fun parsesKeyText() {
        assertEquals(listOf("з", "х", "ъ"), LayoutEditing.parseKey("з х  ъ")!!.outputs.map { it.lower })
        assertEquals(listOf("З", "Х", "Ъ"), LayoutEditing.parseKey("з х ъ")!!.outputs.map { it.upper })
        assertEquals(KeyOutput("ß", "ẞ"), LayoutEditing.parseKey("ß/ẞ")!!.primary)
        assertNull(LayoutEditing.parseKey("   "))
    }

    @Test fun formatIsInverseOfParse() {
        for (t in listOf("з х ъ", "q", "ß/ẞ e")) assertEquals(t, LayoutEditing.formatKey(LayoutEditing.parseKey(t)))
    }

    @Test fun jsonRoundTripKeepsEverything() {
        val ru = TestData.layout("russian_jcuken")
        val again = KeyboardLayout.parse(ru.id, LayoutEditing.toJson(ru))
        assertEquals(ru, again)
        // quotes and backslashes survive
        val odd = ru.copy(name = "Моя \"раскладка\" \\ 1")
        assertEquals(odd.name, KeyboardLayout.parse("x", LayoutEditing.toJson(odd)).name)
    }

    @Test fun keyMapRoundTrip() {
        val m = linkedMapOf("KEYCODE_Q" to "😀", "KEYCODE_W" to "\"", "KEYCODE_E" to "\\")
        assertEquals(m, clack.core.layout.AltLayer.parse(LayoutEditing.keyMapToJson(m, "Эмодзи")).mappings)
    }

    @Test fun idsAreUniqueAndReadable() {
        assertEquals("moya_raskladka", LayoutEditing.idFor("Моя раскладка", emptySet()))
        assertEquals("moya_raskladka_2", LayoutEditing.idFor("Моя раскладка", setOf("moya_raskladka")))
        assertEquals("layout", LayoutEditing.idFor("!!!", emptySet()))
    }

    @Test fun gridCoversAllLetterKeys() {
        val keys = LayoutEditing.TITAN2_ROWS.flatten().filter { it != LayoutEditing.SPACE_GAP }
        assertEquals(26, keys.toSet().size)
        assertEquals(TestData.layout("qwerty").mappings.keys, keys.toSet())
    }
}
