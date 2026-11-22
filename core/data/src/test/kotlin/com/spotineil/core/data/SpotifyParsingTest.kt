package com.spotineil.core.data

import com.spotineil.core.data.spotifyweb.playlistFrom
import com.spotineil.core.data.spotifyweb.trackFrom
import com.spotineil.core.data.spotifyweb.trackFromEntry
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import kotlinx.serialization.json.Json

class SpotifyParsingTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test fun `playlist with songs count and cover`() {
        val p = playlistFrom(parse("""{"id":"37i9","name":"Road trip","owner":{"display_name":"Vince"},"tracks":{"total":42},"images":[{"url":"https://i/1.jpg"}]}"""))
        assertNotNull(p)
        assertEquals("Road trip", p!!.name)
        assertEquals("Vince", p!!.owner)
        assertEquals(42, p!!.trackCount)
        assertEquals("https://i/1.jpg", p!!.imageUrl)
    }

    @Test fun `playlist count also read from items total`() {
        val p = playlistFrom(parse("""{"id":"a","name":"X","items":{"total":7}}"""))
        assertEquals(7, p?.trackCount)
    }

    @Test fun `liked song entry becomes a track`() {
        val t = trackFromEntry(parse("""{"added_at":"2024","track":{"id":"t1","name":"Wherever You Will Go","type":"track","duration_ms":208000,"artists":[{"name":"The Calling"}],"album":{"name":"Camino","images":[{"url":"big"},{"url":"mid"},{"url":"small"}]}}}"""))
        assertNotNull(t)
        assertEquals("Wherever You Will Go", t!!.title)
        assertEquals("The Calling", t!!.artist)
        assertEquals(208000L, t!!.durationMs)
        assertEquals("mid", t!!.imageUrl)
    }

    @Test fun `newer playlist entries use item`() {
        val t = trackFromEntry(parse("""{"item":{"id":"t2","name":"Song","artists":[{"name":"A"},{"name":"B"}],"duration_ms":1000}}"""))
        assertEquals("A, B", t?.artist)
    }

    @Test fun `episodes local files and removed songs are skipped`() {
        assertNull(trackFrom(parse("""{"id":"e1","name":"Episode","type":"episode"}""")))
        assertNull(trackFrom(parse("""{"id":null,"name":"Local file","type":"track"}""")))
        assertNull(trackFromEntry(parse("""{"track":null}""")))
    }
}
