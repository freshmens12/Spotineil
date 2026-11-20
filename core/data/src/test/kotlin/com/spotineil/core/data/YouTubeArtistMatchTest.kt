package com.spotineil.core.data

import com.spotineil.core.data.repository.isBy
import com.spotineil.core.model.YouTubeVideo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeArtistMatchTest {
    private fun song(artist: String) = YouTubeVideo(id = "x", title = "Song", channel = artist, thumbnailUrl = null, durationMs = 200_000)

    @Test
    fun `exact and differently written artist names match`() {
        assertTrue(song("Cup of Joe").isBy("cup of joe"))
        assertTrue(song("BINI").isBy("Bini"))
    }

    @Test
    fun `featured and joint credits match each credited artist`() {
        assertTrue(song("Cup of Joe & Janine").isBy("Cup of Joe"))
        assertTrue(song("Cup of Joe & Janine").isBy("Janine"))
        assertTrue(song("Shanti Dope, Gloc-9").isBy("Gloc-9"))
    }

    @Test
    fun `fan channels and look-alike names do not match`() {
        assertFalse(song("bini.freestylo").isBy("BINI"))
        assertFalse(song("Binibining Pilipinas").isBy("BINI"))
        assertFalse(song("Anyone").isBy(""))
    }
}
