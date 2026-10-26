package io.github.slrmoon.clack

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import clack.core.predict.Tokenizer
import java.io.InputStreamReader

/**
 * Learns the user's vocabulary and word sequences from a Telegram Desktop export
 * (Settings → Advanced → Export Telegram data, format "Machine-readable JSON" → result.json).
 *
 * Only the user's own messages are used when the export says who the user is
 * (full-account exports contain "personal_information"). A single-chat export has no such
 * field; then every message is used. Everything stays on the device.
 */
class TelegramImporter(private val context: Context, private val languages: Languages) {

    data class Result(val messages: Int, val words: Int, val ownOnly: Boolean)

    /** Blocking; call from a background thread. [progress] receives the number of messages read so far. */
    fun import(uri: Uri, progress: (Int) -> Unit): Result {
        val ownId = findOwnId(uri)
        val ru = waitFor("ru")
        val en = waitFor("en")
        var messages = 0
        var words = 0
        open(uri).use { reader ->
            walkMessages(reader) { fromId, text ->
                if (ownId != null && fromId != "user$ownId") return@walkMessages
                if (text.isBlank()) return@walkMessages
                val lang = if (isMostlyCyrillic(text)) ru else en
                if (lang != null) words += Tokenizer.learnText(lang.user, text)
                messages++
                if (messages % 500 == 0) progress(messages)
            }
        }
        ru?.engine?.refreshUserIndex()
        en?.engine?.refreshUserIndex()
        languages.saveNow()
        return Result(messages, words, ownId != null)
    }

    private fun waitFor(code: String): Languages.Lang? {
        languages.get(code)?.let { return it }
        val latch = java.util.concurrent.CountDownLatch(1)
        languages.ensure(code) { latch.countDown() }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return languages.get(code)
    }

    private fun open(uri: Uri) = JsonReader(InputStreamReader(context.contentResolver.openInputStream(uri)!!, Charsets.UTF_8)).apply { isLenient = true }

    private fun isMostlyCyrillic(s: String): Boolean {
        var cyr = 0; var lat = 0
        for (c in s) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return cyr >= lat
    }

    /** First pass: read personal_information.user_id if present (it comes before the chats). */
    private fun findOwnId(uri: Uri): String? = runCatching {
        open(uri).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "personal_information" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == "user_id") return@use readScalar(r) else r.skipValue()
                        }
                        r.endObject()
                    }
                    "chats", "left_chats", "messages" -> return@use null
                    else -> r.skipValue()
                }
            }
            null
        }
    }.getOrNull()

    /** Streams every message in the export (full export or single chat) without loading it all. */
    private fun walkMessages(r: JsonReader, onMessage: (fromId: String?, text: String) -> Unit) {
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "chats", "left_chats" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "list") {
                            r.beginArray()
                            while (r.hasNext()) readChat(r, onMessage)
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
                "messages" -> readMessages(r, onMessage)
                else -> r.skipValue()
            }
        }
        r.endObject()
    }

    private fun readChat(r: JsonReader, onMessage: (String?, String) -> Unit) {
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "messages") readMessages(r, onMessage) else r.skipValue()
        }
        r.endObject()
    }

    private fun readMessages(r: JsonReader, onMessage: (String?, String) -> Unit) {
        r.beginArray()
        while (r.hasNext()) {
            var type: String? = null
            var from: String? = null
            var text = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> type = readScalar(r)
                    "from_id" -> from = readScalar(r)
                    "text" -> text = readText(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            // forwarded messages are someone else's writing
            if (type == "message") onMessage(from, text)
        }
        r.endArray()
    }

    /** "text" is either a string or an array of strings and {type, text} entities. */
    private fun readText(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING -> r.nextString()
        JsonToken.BEGIN_ARRAY -> buildString {
            r.beginArray()
            while (r.hasNext()) {
                when (r.peek()) {
                    JsonToken.STRING -> append(r.nextString())
                    JsonToken.BEGIN_OBJECT -> {
                        var t = ""; var kind = ""
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "text" -> t = readScalar(r) ?: ""
                            "type" -> kind = readScalar(r) ?: ""
                            else -> r.skipValue()
                        }
                        r.endObject()
                        // links, mentions, code are not natural language
                        if (kind in TEXT_ENTITY_KINDS) append(t) else append(' ')
                    }
                    else -> r.skipValue()
                }
            }
            r.endArray()
        }
        else -> { r.skipValue(); "" }
    }

    private fun readScalar(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    companion object {
        private val TEXT_ENTITY_KINDS = setOf("plain", "bold", "italic", "underline", "strikethrough", "spoiler", "blockquote")
    }
}
