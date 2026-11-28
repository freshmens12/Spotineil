package com.spotineil.core.player.local

import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LyricLineMetadataTest {

    private val song = MediaMetadata.Builder().setTitle("Off The Record").setArtist("IVE").setAlbumTitle("I've Mine").build()

    @Test
    fun `no line keeps the song as it is`() {
        assertSame(song, withLyricLine(song, null))
    }

    @Test
    fun `a line takes the artist's place and the artist moves next to the title`() {
        val shown = withLyricLine(song, "떠나 forbidden island")
        assertEquals("Off The Record · IVE", shown.title.toString())
        assertEquals("Off The Record · IVE", shown.displayTitle.toString())
        assertEquals("떠나 forbidden island", shown.artist.toString())
        assertEquals("I've Mine", shown.albumTitle.toString())
    }

    @Test
    fun `a song without an artist keeps just its title`() {
        val shown = withLyricLine(MediaMetadata.Builder().setTitle("Bae").build(), "♪")
        assertEquals("Bae", shown.title.toString())
        assertEquals("♪", shown.artist.toString())
    }
}
