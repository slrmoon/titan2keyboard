package clack.core

import clack.core.importer.JsonStream
import clack.core.importer.TelegramExport
import clack.core.predict.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class TelegramExportTest {
    // Shape of a Telegram Desktop "Export Telegram data" (JSON), trimmed.
    private val fullExport = """
    {
     "about": "Here is the data you requested.",
     "personal_information": {
      "user_id": 111,
      "first_name": "Din",
      "last_name": "",
      "phone_number": "+7 *** *** **",
      "bio": ""
     },
     "profile_pictures": [ { "date": "2024-01-01T10:00:00", "photo": "profile_pictures/photo_1.jpg" } ],
     "contacts": { "about": "x", "list": [ { "first_name": "Маша", "phone_number": "+7", "date": "2023-01-01T00:00:00" } ] },
     "chats": {
      "about": "This page lists all chats from this export.",
      "list": [
       {
        "name": "Маша",
        "type": "personal_chat",
        "id": 222,
        "messages": [
         { "id": 1, "type": "service", "date": "2024-01-01T10:00:00", "actor": "Din", "actor_id": "user111",
           "action": "phone_call", "duration_seconds": 12, "text": "", "text_entities": [] },
         { "id": 2, "type": "message", "date": "2024-01-01T10:01:00", "date_unixtime": "1704092460",
           "from": "Din", "from_id": "user111", "text": "Доброе утро! Как спалось?",
           "text_entities": [ { "type": "plain", "text": "Доброе утро! Как спалось?" } ] },
         { "id": 3, "type": "message", "date": "2024-01-01T10:02:00", "from": "Маша", "from_id": "user222",
           "reply_to_message_id": 2, "text": "Отлично, а ты?", "text_entities": [] },
         { "id": 4, "type": "message", "date": "2024-01-01T10:03:00", "from": "Din", "from_id": "user111",
           "text": [ "кста ", { "type": "bold", "text": "завтра" }, " созвон, вот ",
                     { "type": "link", "text": "https://meet.example.com/abc" }, " \"ссылка\"\nтут" ],
           "text_entities": [], "reactions": [ { "type": "emoji", "count": 1, "emoji": "👍", "recent": [ { "from": "Маша", "from_id": "user222", "date": "2024-01-01T10:04:00" } ] } ] },
         { "id": 5, "type": "message", "date": "2024-01-01T10:05:00", "from": "Din", "from_id": "user111",
           "forwarded_from": "Новости", "text": "Курс валют вырос", "text_entities": [] },
         { "id": 6, "type": "message", "date": "2024-01-01T10:06:00", "from": "Din", "from_id": "user111",
           "photo": "photos/photo_1.jpg", "width": 1280, "height": 960, "text": "", "text_entities": [] },
         { "id": 7, "type": "message", "date": "2024-01-01T10:07:00", "from": "Din", "from_id": "user111",
           "via_bot": "@gif", "text": "смешная гифка", "text_entities": [] },
         { "id": 8, "type": "message", "date": "2024-01-01T10:08:00", "from": "Din", "from_id": "user111",
           "edited": "2024-01-01T10:09:00", "text": "See you tomorrow", "text_entities": [], "is_pinned": true, "sticker_emoji": null }
        ]
       }
      ]
     },
     "left_chats": { "about": "x", "list": [] }
    }
    """.trimIndent()

    private val singleChat = """
    { "name": "Маша", "type": "personal_chat", "id": 222,
      "messages": [
        { "id": 1, "type": "message", "from": "Din", "from_id": "user111", "text": "Доброе утро" },
        { "id": 2, "type": "message", "from": "Маша", "from_id": "user222", "text": "Привет" }
      ] }
    """.trimIndent()

    @Test fun findsOwnerInFullExport() {
        assertEquals("user111", TelegramExport.ownId(StringReader(fullExport)))
        assertEquals(null, TelegramExport.ownId(StringReader(singleChat)))
    }

    @Test fun readsOnlyRealMessages() {
        val got = ArrayList<TelegramExport.Message>()
        TelegramExport.forEachMessage(StringReader(fullExport)) { got.add(it) }
        val texts = got.map { it.text }
        assertEquals(4, got.size) // 2, 3, 4, 8 — not service, forwarded, empty photo or via bot
        assertTrue(texts.contains("Доброе утро! Как спалось?"))
        assertFalse(texts.any { "Курс валют" in it })
        assertFalse(texts.any { "гифка" in it })
        val m4 = texts.first { it.startsWith("кста") }
        assertTrue("bold kept: $m4", "завтра" in m4)
        assertFalse("link dropped: $m4", "https" in m4)
        assertTrue("escapes decoded: $m4", "\"ссылка\"\nтут" in m4)
    }

    @Test fun learnsOnlyOwnMessagesFromFullExport() {
        val ru = UserModel(); val en = UserModel()
        val stats = TelegramExport.learn({ StringReader(fullExport) }, ru, en)
        assertTrue(stats.ownOnly)
        assertEquals(3, stats.messages)
        assertEquals("утро", ru.next(listOf("доброе"), 1).first().first)
        assertEquals(0, ru.count("отлично")) // Маша's words are not learned
        assertEquals(0, ru.count("курс"))    // forwarded
        assertTrue(ru.count("кста") > 0)
        assertTrue(en.count("tomorrow") > 0)
    }

    @Test fun singleChatLearnsEverything() {
        val ru = UserModel()
        val stats = TelegramExport.learn({ StringReader(singleChat) }, ru, null)
        assertFalse(stats.ownOnly)
        assertEquals(2, stats.messages)
        assertTrue(ru.count("привет") > 0)
    }

    @Test fun streamReaderHandlesNestingAndScalars() {
        val r = JsonStream(StringReader("""{"a":[1,-2.5e3,true,false,null,{"b":[]}],"c":"\u00e9\/"}"""))
        r.beginObject()
        assertEquals("a", r.nextName())
        r.beginArray()
        assertEquals("1", r.nextString()); assertEquals("-2.5e3", r.nextString())
        assertEquals("true", r.nextString()); assertEquals("false", r.nextString()); r.nextNull()
        r.skipValue()
        assertFalse(r.hasNext()); r.endArray()
        assertEquals("c", r.nextName()); assertEquals("é/", r.nextString())
        r.endObject()
        assertEquals(JsonStream.Token.END, r.peek())
    }

    @Test fun bigExportStreamsQuickly() {
        val n = 100_000
        val sb = StringBuilder("""{"personal_information":{"user_id":1},"chats":{"list":[{"name":"x","messages":[""")
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            val from = if (i % 3 == 0) "user2" else "user1"
            sb.append("""{"id":$i,"type":"message","from_id":"$from","text":["привет ",{"type":"bold","text":"как дела"}," сегодня"],"text_entities":[]}""")
        }
        sb.append("]}]}}")
        val ru = UserModel()
        val t0 = System.nanoTime()
        val stats = TelegramExport.learn({ StringReader(sb.toString()) }, ru, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n - (n + 2) / 3, stats.messages)
        assertTrue("took $ms ms", ms < 20_000)
        assertEquals("дела", ru.next(listOf("привет", "как"), 1).first().first)
    }
}
