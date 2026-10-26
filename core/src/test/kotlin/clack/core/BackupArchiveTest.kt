package clack.core

import clack.core.backup.BackupArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @Test fun roundTripKeepsEverything() {
        val prefs = mapOf(
            "clack" to mapOf<String, Any?>(
                "layouts" to "russian_translit,qwerty", "show_bar" to false, "multitap_ms" to 600,
                "touched" to 1729900000000L, "f" to 1.5f, "set" to setOf("a", "b\"c"), "quote" to "a\\b\n\"c\""
            ),
            "clack_launch" to mapOf<String, Any?>("KEYCODE_T" to "org.telegram.messenger/org.telegram.ui.LaunchActivity")
        )
        val files = mapOf(
            "user_ru.tsv" to "u\tпривет\t5\nb\t<s>\tпривет\t3\n".toByteArray(),
            "layouts/moya.json" to "{\"name\":\"Моя\",\"mappings\":{}}".toByteArray(),
            "sym_page2.json" to "{\"mappings\":{\"KEYCODE_Q\":\"😀\"}}".toByteArray()
        )
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, BackupArchive.Content("0.1.20", "2026-10-26", prefs, files))
        val back = BackupArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("0.1.20", back.appVersion)
        assertEquals(prefs, back.prefs)
        assertEquals(files.keys, back.files.keys)
        for ((k, v) in files) assertArrayEquals(v, back.files[k])
    }

    @Test fun rejectsForeignZipAndUnsafePaths() {
        val plain = ByteArrayOutputStream()
        ZipOutputStream(plain).use { it.putNextEntry(ZipEntry("photo.jpg")); it.write(1); it.closeEntry() }
        val e = runCatching { BackupArchive.read(ByteArrayInputStream(plain.toByteArray())) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)

        // a crafted archive cannot write outside the app folder
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { z ->
            z.putNextEntry(ZipEntry(BackupArchive.MARKER)); z.write("{\"format\":1}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("files/../../shared_prefs/x.xml")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("files/ok.json")); z.write(2); z.closeEntry()
        }
        val c = BackupArchive.read(ByteArrayInputStream(evil.toByteArray()))
        assertEquals(setOf("ok.json"), c.files.keys)
        assertFalse(BackupArchive.isSafePath("/etc/x"))
        assertFalse(BackupArchive.isSafePath("a/../b"))
        assertTrue(BackupArchive.isSafePath("layouts/moya.json"))
    }
}
