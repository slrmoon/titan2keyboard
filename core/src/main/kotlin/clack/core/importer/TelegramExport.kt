package clack.core.importer

import clack.core.predict.Tokenizer
import clack.core.predict.UserModel
import java.io.Reader

/**
 * Reads a Telegram Desktop export ("Machine-readable JSON", result.json) as a stream.
 *
 * Full account export:
 *   { "personal_information": { "user_id": 123, ... }, ...,
 *     "chats": { "list": [ { "name": ..., "messages": [ {...}, ... ] } ] }, "left_chats": {...} }
 * Single chat export:
 *   { "name": ..., "type": ..., "id": ..., "messages": [ ... ] }
 * Message:
 *   { "type": "message" | "service", "from_id": "user123", "forwarded_from": "...",
 *     "text": "plain" | [ "plain", { "type": "bold" | "link" | ..., "text": "..." }, ... ] }
 */
object TelegramExport {
    data class Message(val fromId: String?, val text: String)

    /** Text entities that are natural language; links, mentions, code etc. are left out. */
    private val TEXT_KINDS = setOf("plain", "bold", "italic", "underline", "strikethrough", "spoiler", "blockquote", "text_link")

    /** The exporting user's id ("user123"), present only in full-account exports. Reads just the head of the file. */
    fun ownId(reader: Reader): String? = JsonStream(reader).use { r ->
        if (r.peek() != JsonStream.Token.BEGIN_OBJECT) return null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "personal_information" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "user_id") {
                            val id = scalar(r) ?: return null
                            return "user$id"
                        } else r.skipValue()
                    }
                    r.endObject()
                }
                // personal_information comes before the chats; if chats start first there is none
                "chats", "left_chats", "messages" -> return null
                else -> r.skipValue()
            }
        }
        null
    }

    /** Every message written by a person (not service events, not forwarded from someone else). */
    fun forEachMessage(reader: Reader, onMessage: (Message) -> Unit) = JsonStream(reader).use { r ->
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "chats", "left_chats" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "list") {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginObject()
                                while (r.hasNext()) if (r.nextName() == "messages") messages(r, onMessage) else r.skipValue()
                                r.endObject()
                            }
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
                "messages" -> messages(r, onMessage)
                else -> r.skipValue()
            }
        }
        r.endObject()
    }

    private fun messages(r: JsonStream, onMessage: (Message) -> Unit) {
        r.beginArray()
        while (r.hasNext()) {
            var type: String? = null
            var from: String? = null
            var text = ""
            var forwarded = false
            var viaBot = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> type = scalar(r)
                    "from_id" -> from = scalar(r)
                    "text" -> text = text(r)
                    "forwarded_from", "forwarded_from_id" -> { forwarded = true; r.skipValue() }
                    "via_bot" -> { viaBot = true; r.skipValue() }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (type == "message" && !forwarded && !viaBot && text.isNotBlank()) onMessage(Message(from, text))
        }
        r.endArray()
    }

    private fun text(r: JsonStream): String = when (r.peek()) {
        JsonStream.Token.STRING -> r.nextString()
        JsonStream.Token.BEGIN_ARRAY -> buildString {
            r.beginArray()
            while (r.hasNext()) {
                when (r.peek()) {
                    JsonStream.Token.STRING -> append(r.nextString())
                    JsonStream.Token.BEGIN_OBJECT -> {
                        var t = ""; var kind = ""
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "text" -> t = scalar(r) ?: ""
                            "type" -> kind = scalar(r) ?: ""
                            else -> r.skipValue()
                        }
                        r.endObject()
                        append(if (kind in TEXT_KINDS) t else " ")
                    }
                    else -> r.skipValue()
                }
            }
            r.endArray()
        }
        else -> { r.skipValue(); "" }
    }

    private fun scalar(r: JsonStream): String? = when (r.peek()) {
        JsonStream.Token.STRING, JsonStream.Token.NUMBER, JsonStream.Token.BOOLEAN -> r.nextString()
        JsonStream.Token.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    data class Stats(val messages: Int, val words: Int, val ownOnly: Boolean)

    /**
     * Learn from an export. [open] is called twice (head for the owner id, then the whole file).
     * Messages go to [ru] or [en] by script; [progress] gets the number of messages learned so far.
     */
    fun learn(open: () -> Reader, ru: UserModel?, en: UserModel?, progress: (Int) -> Unit = {}): Stats {
        val own = runCatching { ownId(open()) }.getOrNull()
        var messages = 0
        var words = 0
        forEachMessage(open()) { m ->
            if (own != null && m.fromId != own) return@forEachMessage
            val model = if (isMostlyCyrillic(m.text)) ru else en
            if (model != null) words += Tokenizer.learnText(model, m.text)
            messages++
            if (messages % 500 == 0) progress(messages)
        }
        return Stats(messages, words, own != null)
    }

    fun isMostlyCyrillic(s: String): Boolean {
        var cyr = 0; var lat = 0
        for (c in s) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return cyr >= lat
    }
}
