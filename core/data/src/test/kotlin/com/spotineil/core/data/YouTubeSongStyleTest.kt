package com.spotineil.core.data

import com.spotineil.core.data.repository.mixArtists
import com.spotineil.core.data.repository.songStyle
import com.spotineil.core.data.repository.unescapeHtml
import com.spotineil.core.model.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun yt(id: String, title: String, channel: String) = YouTubeVideo(id, title, channel, null, 200_000)

class YouTubeSongStyleTest {

    @Test fun `official Topic audio is ranked above lyric re-uploads of the same song and duplicates collapse`() {
        val results = listOf(
            yt("lyrics", "Nova Lane - Paper Moon (Lyrics)", "Lyric Vault"),
            yt("other", "Nova Lane - Glass Rivers (Official Video)", "Nova Lane"),
            yt("topic", "Paper Moon", "Nova Lane - Topic"),
        )

        val ranked = results.songStyle(query = "nova lane")

        assertEquals("topic", ranked.first().id)
        // "Paper Moon" appears once (the Topic version), the other song stays.
        assertEquals(listOf("topic", "other"), ranked.map { it.id })
    }

    @Test fun `karaoke and medleys sink in search, unless that's what was searched for`() {
        val results = listOf(
            yt("karaoke", "Paper Moon - Nova Lane (Karaoke Version)", "Sing Along"),
            yt("song", "Nova Lane - Paper Moon", "Nova Lane"),
        )

        assertEquals("song", results.songStyle(query = "paper moon").first().id)
        assertEquals("karaoke", results.songStyle(query = "paper moon karaoke").first().id)
    }

    @Test fun `radio leaves out junk and mixes artists`() {
        val results = listOf(
            yt("a1", "First Light", "Nova Lane - Topic"),
            yt("a2", "Second Wind", "Nova Lane - Topic"),
            yt("medley", "NOVA LANE MEDLEY | Best Songs Playlist", "Mix Channel"),
            yt("b1", "Harbor Lights", "Kai Ember - Topic"),
            yt("a3", "Third Act", "Nova Lane - Topic"),
            yt("b2", "Ember Road", "Kai Ember - Topic"),
        )

        val station = results.songStyle(query = "", dropJunk = true).mixArtists()

        assertFalse(station.any { it.id == "medley" })
        assertEquals(5, station.size)
        // Never the same artist twice in a row while another artist is left.
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), station.map { it.id })
    }

    @Test fun `HTML escaped titles from the Data API become plain text`() {
        assertEquals("I'm Fine & You?", unescapeHtml("I&#39;m Fine &amp; You?"))
        assertEquals("\"Quoted\" <b>", unescapeHtml("&quot;Quoted&quot; &lt;b&gt;"))
        assertEquals("No entities here", unescapeHtml("No entities here"))
    }

    @Test fun `Topic uploads are recognised as songs`() {
        assertTrue(yt("x", "Paper Moon", "Nova Lane - Topic").isTopic)
        assertFalse(yt("y", "Nova Lane - Paper Moon (Official Video)", "NovaLaneVEVO").isTopic)
        assertEquals("Nova Lane", yt("x", "Paper Moon", "Nova Lane - Topic").artist)
    }
}
