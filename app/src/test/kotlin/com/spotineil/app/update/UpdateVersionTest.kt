package com.spotineil.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun `newer versions are detected number by number`() {
        assertTrue(isNewerVersion("1.2.0", "1.1.9"))
        assertTrue(isNewerVersion("1.10.0", "1.9.2"))
        assertTrue(isNewerVersion("2.0", "1.99.99"))
    }

    @Test
    fun `same or older versions are not updates, and suffixes are ignored`() {
        assertFalse(isNewerVersion("1.2.0", "1.2.0-debug"))
        assertFalse(isNewerVersion("1.1.0", "1.2.0"))
        assertFalse(isNewerVersion("1.2", "1.2.0"))
    }

    @Test
    fun `release answer is read into an update`() {
        val update = parseRelease(
            """{"tag_name":"v1.3.0","body":" New: History ","assets":[
                {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":5},
                {"name":"Spotineil.apk","browser_download_url":"https://x/Spotineil.apk","size":1234}]}""",
        )!!
        assertEquals("1.3.0", update.versionName)
        assertEquals("New: History", update.notes)
        assertEquals("https://x/Spotineil.apk", update.apkUrl)
        assertEquals(1234L, update.sizeBytes)
    }

    @Test
    fun `a release without an apk or with broken data is ignored`() {
        assertNull(parseRelease("""{"tag_name":"v1.3.0","assets":[]}"""))
        assertNull(parseRelease("not json"))
    }
}
