package com.spotineil.core.player.youtube

import com.spotineil.core.model.PlayableMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeDownloaderTest {

    private fun media(mediaId: String, uri: String) = PlayableMedia(mediaId, uri, "Song", "Singer")

    @Test
    fun youTubeSongsAreRecognised() {
        assertEquals("dQw4w9WgXcQ", YouTubeDownloader.videoId(media("youtube:dQw4w9WgXcQ", "ytmusic:video:dQw4w9WgXcQ")))
        assertEquals("a_b-C1", YouTubeDownloader.videoId(media("youtube:a_b-C1", "https://example.com/x.mp3")))
    }

    @Test
    fun otherSongsAreNot() {
        assertNull(YouTubeDownloader.videoId(media("jamendo:1", "https://example.com/x.mp3")))
        assertNull(YouTubeDownloader.videoId(media("local:1", "content://media/external/audio/media/1")))
        assertNull(YouTubeDownloader.videoId(media("youtube:../x", "ytmusic:video:../x")))
    }

    @Test
    fun filesAreNamedArtistDashTitle() {
        assertEquals("The Script - Breakeven", YouTubeDownloader.fileBaseName("Breakeven", "The Script", "id"))
        assertEquals("AC_DC - What_ Now_", YouTubeDownloader.fileBaseName("What? Now*", "AC/DC", "id"))
        assertEquals("id", YouTubeDownloader.fileBaseName("", "", "id"))
    }
}
