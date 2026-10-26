package clack.core

import clack.core.dict.Trie
import clack.core.dict.WordList
import clack.core.layout.AltLayer
import clack.core.layout.KeyboardLayout
import clack.core.predict.UserModel
import clack.core.typo.KeyGeometry
import clack.core.typo.TypoModel
import java.io.File
import java.util.zip.GZIPInputStream

/** Loads the real bundled assets, shared between tests (dictionary loading is the slow part). */
object TestData {
    private val assets = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }

    fun layout(name: String): KeyboardLayout = KeyboardLayout.parse(name, File(assets, "layouts/$name.json").readText())
    fun altLayer(): AltLayer = AltLayer.parse(File(assets, "alt/titan2.json").readText())

    val ruTrie: Trie by lazy { trie("ru") }
    val enTrie: Trie by lazy { trie("en") }

    private fun trie(lang: String): Trie =
        GZIPInputStream(File(assets, "dict/$lang.tsv.gz").inputStream()).bufferedReader().use { Trie.build(WordList.read(it)) }

    fun ruEngine(user: UserModel = UserModel()) =
        SuggestionEngine(ruTrie, user, TypoModel(layout("russian_jcuken"), KeyGeometry.TITAN2)).also { it.refreshUserIndex() }

    fun enEngine(user: UserModel = UserModel()) =
        SuggestionEngine(enTrie, user, TypoModel(layout("qwerty"), KeyGeometry.TITAN2)).also { it.refreshUserIndex() }
}
