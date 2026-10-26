package io.github.slrmoon.clack

import android.content.Context
import android.util.Log
import clack.core.SuggestionEngine
import clack.core.dict.Trie
import clack.core.dict.WordList
import clack.core.layout.KeyboardLayout
import clack.core.predict.UserModel
import clack.core.typo.KeyGeometry
import clack.core.typo.TypoModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Per-language dictionaries and learned models. Loading takes a moment (≈100k words),
 * so it happens on a background thread; until then the keyboard simply types without suggestions.
 * One instance is shared by the keyboard service and the settings screen.
 */
class Languages private constructor(private val context: Context) {
    class Lang(val code: String, val engine: SuggestionEngine, val user: UserModel) {
        @Volatile var savedVersion = user.version
    }

    private val langs = ConcurrentHashMap<String, Lang>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "clack-lang").apply { priority = Thread.NORM_PRIORITY - 1 } }

    fun get(code: String): Lang? = langs[code]

    /** Starts loading [code] if needed; [onReady] runs on the worker thread. */
    fun ensure(code: String, onReady: ((Lang) -> Unit)? = null) {
        langs[code]?.let { l -> onReady?.let { worker.execute { it(l) } }; return }
        if (!loading.add(code)) { onReady?.let { cb -> worker.execute { langs[code]?.let(cb) } }; return }
        worker.execute {
            try {
                val t0 = System.currentTimeMillis()
                val words = readWordList(code)
                val trie = Trie.build(words)
                val user = UserModel()
                val f = file(code)
                if (f.exists()) runCatching { f.bufferedReader().use(user::read) }
                // placeholder costs until a layout is attached
                val engine = SuggestionEngine(trie, user, TypoModel(KeyboardLayout("none", "", "", emptyMap()), KeyGeometry.TITAN2))
                engine.refreshUserIndex()
                val lang = Lang(code, engine, user)
                lang.savedVersion = user.version
                langs[code] = lang
                Log.i(TAG, "loaded $code: ${trie.size} words, ${trie.nodeCount} nodes in ${System.currentTimeMillis() - t0} ms")
                onReady?.invoke(lang)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load $code", t)
            } finally {
                loading.remove(code)
            }
        }
    }

    fun attachLayout(layout: KeyboardLayout) {
        ensure(layout.language) { it.engine.costs = TypoModel(layout, KeyGeometry.TITAN2) }
    }

    /** Write learned data of every language that changed. Runs on the worker. */
    fun saveAsync() = worker.execute { saveNow() }

    fun saveNow() {
        for (l in langs.values) {
            if (l.user.version == l.savedVersion) continue
            val f = file(l.code)
            val tmp = File(f.path + ".tmp")
            runCatching {
                tmp.bufferedWriter().use(l.user::write)
                tmp.renameTo(f)
                l.savedVersion = l.user.version
            }.onFailure { Log.e(TAG, "save ${l.code}", it) }
        }
    }

    fun clearLearned() = worker.execute {
        for (l in langs.values) { l.user.clear(); l.engine.refreshUserIndex() }
        context.filesDir.listFiles { f -> f.name.startsWith("user_") }?.forEach { it.delete() }
        for (l in langs.values) l.savedVersion = l.user.version
    }

    /**
     * The build tools unpack "*.tsv.gz" assets and store them as "*.tsv" (compressed inside the APK
     * anyway), so look for the plain name first and fall back to the gzipped one.
     */
    private fun readWordList(code: String): Map<String, Float> {
        runCatching { return context.assets.open("dict/$code.tsv").bufferedReader().use(WordList::read) }
        runCatching { return context.assets.open("dict/$code.tsv.gz").use { GZIPInputStream(it).bufferedReader().use(WordList::read) } }
        Log.e(TAG, "no dictionary for $code")
        return emptyMap()
    }

    private fun file(code: String) = File(context.filesDir, "user_$code.tsv")

    companion object {
        private const val TAG = "Clack"
        @Volatile private var instance: Languages? = null
        fun get(context: Context): Languages =
            instance ?: synchronized(this) { instance ?: Languages(context.applicationContext).also { instance = it } }
    }
}
