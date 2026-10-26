package clack.core

import clack.core.update.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val sample = """
        {"tag_name":"latest","name":"Clack 0.1.12","body":"Fix double space\n\nmore",
         "assets":[{"name":"clack.apk","size":994916,
                    "browser_download_url":"https://github.com/slrmoon/titan2keyboard/releases/download/latest/clack.apk"}]}
    """.trimIndent()

    @Test fun parsesLatestRelease() {
        val r = Release.parse(sample)!!
        assertEquals("0.1.12", r.versionName)
        assertEquals(12, r.versionCode)
        assertEquals(994916L, r.apkSize)
        assertTrue(r.apkUrl.endsWith("/clack.apk"))
        assertTrue(r.notes.startsWith("Fix double space"))
        assertTrue(r.isNewerThan(11))
        assertFalse(r.isNewerThan(12))
    }

    @Test fun rejectsReleaseWithoutApk() {
        assertNull(Release.parse("""{"name":"Clack 0.1.3","assets":[]}"""))
        assertNull(Release.parse("""{"name":"no version","assets":[{"name":"a.apk","browser_download_url":"x"}]}"""))
    }
}
