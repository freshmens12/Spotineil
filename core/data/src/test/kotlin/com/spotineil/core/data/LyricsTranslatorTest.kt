package com.spotineil.core.data

import com.spotineil.core.data.repository.LyricsTranslator
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTranslatorTest {
    // A real answer of the translate endpoint (two Tagalog lines to English).
    private val answer = """[[["you teach me\n","Tinuturuan mo ako\n",null,null,3],["Straight to you","Dederetso na sa inyo",null,null,3]],null,"tl",null,null,null,1,[],[["tl"],null,[1],["tl"]]]"""

    @Test fun `lines come back one per original line, with the detected language`() {
        val result = LyricsTranslator().parse(answer, expectedLines = 2)
        assertEquals(listOf("you teach me", "Straight to you"), result.lines)
        assertEquals("tl", result.sourceLanguage)
    }

    @Test fun `line count always matches the lyrics`() {
        assertEquals(3, LyricsTranslator().parse(answer, expectedLines = 3).lines.size)
        assertEquals(listOf("you teach me"), LyricsTranslator().parse(answer, expectedLines = 1).lines)
    }
}
