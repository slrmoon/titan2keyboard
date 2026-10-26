package io.github.slrmoon.clack

import android.content.Context
import android.net.Uri
import clack.core.importer.TelegramExport
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Learns the user's vocabulary and word sequences from a Telegram Desktop export
 * (Settings → Advanced → Export Telegram data, format "Machine-readable JSON" → result.json).
 * Parsing lives in core (clack.core.importer.TelegramExport) where it is unit-tested.
 * Everything stays on the device.
 */
class TelegramImporter(private val context: Context, private val languages: Languages) {

    data class Result(val messages: Int, val words: Int, val ownOnly: Boolean)

    /** Blocking; call from a background thread. [progress] receives the number of messages read so far. */
    fun import(uri: Uri, progress: (Int) -> Unit): Result {
        val ru = waitFor("ru")
        val en = waitFor("en")
        val stats = TelegramExport.learn(
            open = { InputStreamReader(context.contentResolver.openInputStream(uri)!!, Charsets.UTF_8) },
            ru = ru?.user,
            en = en?.user,
            progress = progress
        )
        ru?.engine?.refreshUserIndex()
        en?.engine?.refreshUserIndex()
        languages.saveNow()
        return Result(stats.messages, stats.words, stats.ownOnly)
    }

    private fun waitFor(code: String): Languages.Lang? {
        languages.get(code)?.let { return it }
        val latch = CountDownLatch(1)
        languages.ensure(code) { latch.countDown() }
        latch.await(30, TimeUnit.SECONDS)
        return languages.get(code)
    }
}
