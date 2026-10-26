package clack.core

import clack.core.predict.Tokenizer
import clack.core.predict.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {
    private fun autoFix(engine: SuggestionEngine, typed: String, ctx: List<String> = emptyList()) =
        engine.suggest(ctx, typed).autoCorrect?.word

    @Test fun neighborKeyTyposAreFixed() {
        val e = TestData.ruEngine()
        // one or two slips onto keys next to the intended ones
        // "пртвкт": и→т and е→к, both onto neighbouring keys (two slips in one word)
        for (typo in listOf("пртвет", "приает", "птивет", "привеи", "прмвет", "пртвкт", "ппивет")) {
            assertEquals("for '$typo'", "привет", autoFix(e, typo))
        }
    }

    @Test fun transpositionAndDoublePressAreFixed() {
        val e = TestData.ruEngine()
        assertEquals("првиет", "привет", autoFix(e, "првиет"))
        assertEquals("приввет", "привет", autoFix(e, "приввет"))
        assertEquals("сегодян", "сегодня", autoFix(e, "сегодян"))
    }

    @Test fun missingAndExtraLettersAreFixed() {
        val e = TestData.ruEngine()
        assertEquals("здраствуйте", "здравствуйте", autoFix(e, "здраствуйте"))
        assertEquals("пожалуста", "пожалуйста", autoFix(e, "пожалуста"))
        assertEquals("спасиибо", "спасибо", autoFix(e, "спасиибо"))
    }

    @Test fun multiTapMiscountIsFixed() {
        val e = TestData.ruEngine()
        // ж is the second tap on the д key; typing д instead is a cheap slip
        assertEquals("скадите", "скажите", autoFix(e, "скадите"))
        assertEquals("хорощо → хорошо", "хорошо", autoFix(e, "хорощо"))
    }

    @Test fun knownWordsAreNeverAutoCorrected() {
        val e = TestData.ruEngine()
        for (w in listOf("привет", "пока", "мир", "кот", "код", "дом", "кто", "мама", "сон", "тут", "там", "мел", "стих", "лось")) {
            assertNull("'$w' must stay", e.suggest(emptyList(), w).autoCorrect)
        }
    }

    @Test fun yoIsRestored() {
        val e = TestData.ruEngine()
        val s = e.suggest(emptyList(), "еще")
        assertTrue("ещё offered: ${s.items}", s.items.any { it.word == "ещё" } || s.items.any { it.word == "еще" })
    }

    @Test fun completionsAppearWhileTyping() {
        val e = TestData.ruEngine()
        val s = e.suggest(emptyList(), "прив")
        assertTrue("got ${s.items.map { it.word }}", s.items.any { it.word == "привет" })
        val s2 = e.suggest(emptyList(), "здравс")
        assertTrue("got ${s2.items.map { it.word }}", s2.items.any { it.word.startsWith("здравству") })
    }

    @Test fun completionsTolerateTypos() {
        val e = TestData.ruEngine()
        val s = e.suggest(emptyList(), "пртве")
        assertTrue("got ${s.items.map { it.word }}", s.items.any { it.word == "привет" })
    }

    @Test fun learnedWordsAreKeptAndSuggested() {
        val user = UserModel()
        Tokenizer.learnText(user, "кста завтра созвон. кста завтра в силе")
        val e = TestData.ruEngine(user)
        assertNull("'кста' learned, not corrected", e.suggest(emptyList(), "кста").autoCorrect)
        val s = e.suggest(emptyList(), "кс")
        assertTrue("got ${s.items.map { it.word }}", s.items.any { it.word == "кста" })
    }

    @Test fun unknownWordIsOfferedAsIs() {
        val e = TestData.ruEngine()
        val s = e.suggest(emptyList(), "пртвет")
        assertEquals("пртвет", s.unknownTyped)
        assertEquals("привет", s.items.first().word)
    }

    @Test fun nextWordFromLearnedText() {
        val user = UserModel()
        Tokenizer.learnText(user, "Доброе утро! Как дела? Доброе утро, как спалось. Доброе утро")
        val e = TestData.ruEngine(user)
        assertEquals("утро", e.predictNext(listOf("доброе")).first().word)
        assertEquals("доброе", e.predictNext(emptyList()).first().word)
    }

    @Test fun contextBreaksTies() {
        val user = UserModel()
        repeat(5) { Tokenizer.learnText(user, "пойдём в кино") }
        val e = TestData.ruEngine(user)
        val s = e.suggest(listOf("пойдём", "в"), "кмно")
        assertEquals("кино", s.autoCorrect?.word)
    }

    @Test fun translitLayoutFixesItsOwnNeighbours() {
        val e = clack.core.SuggestionEngine(TestData.ruTrie, UserModel(),
            clack.core.typo.TypoModel(TestData.layout("russian_translit"), clack.core.typo.KeyGeometry.TITAN2))
        // translit: и on I, у on U (neighbours); е on E, д on D (neighbours)
        assertEquals("прувет", "привет", autoFix(e, "прувет"))
        assertEquals("привдт", "привет", autoFix(e, "привдт"))
        // multi-tap slip: а and я share a key
        assertEquals("приатная", "приятная", autoFix(e, "приатная"))
    }

    @Test fun letterOnTwoKeysUsesTheCloserOne() {
        // a layout may put one letter on two keys: the cost uses whichever key is closer
        val json = """{"name":"t","mappings":{
            "KEYCODE_Q":{"lowercase":"x","uppercase":"X"},
            "KEYCODE_M":{"lowercase":"x","uppercase":"X"},
            "KEYCODE_W":{"lowercase":"a","uppercase":"A"},
            "KEYCODE_N":{"lowercase":"b","uppercase":"B"}}}"""
        val m = clack.core.typo.TypoModel(clack.core.layout.KeyboardLayout.parse("t", json), clack.core.typo.KeyGeometry.TITAN2)
        assertEquals(clack.core.typo.TypoModel.NEIGHBOR_COST, m.substitution('x', 'a')) // via Q–W
        assertEquals(clack.core.typo.TypoModel.NEIGHBOR_COST, m.substitution('x', 'b')) // via M–N
    }

    @Test fun englishWorksToo() {
        val e = TestData.enEngine()
        assertEquals("hello", autoFix(e, "hrllo"))
        assertEquals("the", autoFix(e, "teh"))
        assertEquals("keyboard", autoFix(e, "keybaord"))
        assertNull(e.suggest(emptyList(), "hello").autoCorrect)
    }

    @Test fun casingIsRestored() {
        assertEquals("Привет", SuggestionEngine.applyCasing("привет", "Пртвет"))
        assertEquals("ПРИВЕТ", SuggestionEngine.applyCasing("привет", "ПРТВЕТ"))
        assertEquals("привет", SuggestionEngine.applyCasing("привет", "пртвет"))
        assertEquals("Привет", SuggestionEngine.applyCasing("привет", "п", capitalizeFirst = true))
    }

    @Test fun shortInputsStayConservative() {
        val e = TestData.ruEngine()
        for (w in listOf("в", "и", "к", "но", "да")) assertNull("'$w'", e.suggest(emptyList(), w).autoCorrect)
        assertFalse(e.suggest(emptyList(), "я").items.any { it.kind == CandidateKind.CORRECTION && it.word.length == 1 && it.word != "я" && it.cost > 0.5f })
    }
}
