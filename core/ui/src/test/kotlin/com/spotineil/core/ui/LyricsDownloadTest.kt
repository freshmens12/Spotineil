package com.spotineil.core.ui

import com.spotineil.core.model.LyricLine
import com.spotineil.core.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsDownloadTest {

    @Test
    fun syncedLyricsBecomeAnLrcFile() {
        val lyrics = Lyrics(listOf(LyricLine(0, "Intro"), LyricLine(65_432, "Hello"), LyricLine(3_723_999, "")), isSynced = true)
        val file = lyricsFile(lyrics, "Song", "Singer")
        assertEquals("Singer - Song.lrc", file.name)
        assertEquals("[ti:Song]\n[ar:Singer]\n[re:Spotineil]\n[00:00.00]Intro\n[01:05.43]Hello\n[62:03.99]\n", file.text)
    }

    @Test
    fun plainLyricsBecomeATextFile() {
        val lyrics = Lyrics(listOf(LyricLine(0, "One"), LyricLine(0, "Two")), isSynced = false)
        val file = lyricsFile(lyrics, "Song", "Singer")
        assertEquals("Singer - Song.txt", file.name)
        assertEquals("Song\nSinger\n\nOne\nTwo\n", file.text)
    }

    @Test
    fun characterFileNamesCannotHoldAreReplaced() {
        val lyrics = Lyrics(listOf(LyricLine(0, "x")), isSynced = false)
        assertEquals("AC_DC - What_ Now_.txt", lyricsFile(lyrics, "What? Now*", "AC/DC").name)
        assertEquals("Lyrics.txt", lyricsFile(lyrics, "", "").name)
    }
}
