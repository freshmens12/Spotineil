package com.spotineil.core.data

import com.spotineil.core.data.lyrics.LrcParser
import com.spotineil.core.model.LyricLine
import com.spotineil.core.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures use placeholder text only. */
class LrcParserTest {

    @Test
    fun `parses centiseconds, milliseconds and whole seconds`() {
        val lines = LrcParser.parse(
            """
            [00:01.50]line one
            [00:02.250]line two
            [01:03]line three
            """.trimIndent(),
        )
        assertEquals(
            listOf(LyricLine(1_500, "line one"), LyricLine(2_250, "line two"), LyricLine(63_000, "line three")),
            lines,
        )
    }

    @Test
    fun `ignores metadata tags and blank lines`() {
        val lines = LrcParser.parse("[ar:Someone]\n[ti:Something]\n\n[00:05.00]hello\n")
        assertEquals(listOf(LyricLine(5_000, "hello")), lines)
    }

    @Test
    fun `expands multiple timestamps and sorts by time`() {
        val lines = LrcParser.parse("[00:30.00][00:10.00]repeated\n[00:20.00]middle")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.timeMs })
        assertEquals(listOf("repeated", "middle", "repeated"), lines.map { it.text })
    }

    @Test
    fun `applies positive offset so lines appear sooner`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:02.00]a\n[00:00.20]b")
        assertEquals(listOf(0L, 1_500L), lines.map { it.timeMs })
    }

    @Test
    fun `strips enhanced word-level timestamps`() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>one <00:01.50>two")
        assertEquals("one two", lines.single().text)
    }

    @Test
    fun `keeps empty timed lines as instrumental gaps`() {
        val lines = LrcParser.parse("[00:01.00]words\n[00:04.00]")
        assertEquals("", lines[1].text)
    }

    @Test
    fun `toLyrics prefers synced, falls back to plain, handles instrumental`() {
        val synced = LrcParser.toLyrics("[00:01.00]x", "plain", instrumental = false)!!
        assertTrue(synced.isSynced)

        val plain = LrcParser.toLyrics(null, "first\nsecond", instrumental = false)!!
        assertFalse(plain.isSynced)
        assertEquals(listOf("first", "second"), plain.lines.map { it.text })

        assertTrue(LrcParser.toLyrics(null, null, instrumental = true)!!.isInstrumental)
        assertNull(LrcParser.toLyrics(" ", null, instrumental = false))
    }

    @Test
    fun `indexAt finds the active line`() {
        val lyrics = Lyrics(listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c")), isSynced = true)
        assertEquals(-1, lyrics.indexAt(500))
        assertEquals(0, lyrics.indexAt(1_000))
        assertEquals(1, lyrics.indexAt(8_999))
        assertEquals(2, lyrics.indexAt(60_000))
    }
}
