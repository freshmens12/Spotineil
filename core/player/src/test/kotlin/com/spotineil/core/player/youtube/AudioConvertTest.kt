package com.spotineil.core.player.youtube

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConvertTest {

    @Test
    fun mp3EncoderTurnsATwoSecondToneIntoMp3Frames() {
        val rate = 48_000
        val encoder = AudioConvert.Mp3Encoder(channels = 2, sampleRate = rate, bitrateKbps = 192)
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(4096 * 2)
        var t = 0
        repeat((2 * rate) / 4096) {
            for (i in 0 until 4096) {
                val v = (sin(2 * PI * 440 * t / rate) * 12000).toInt().toShort()
                chunk[2 * i] = v
                chunk[2 * i + 1] = v
                t++
            }
            encoder.encode(chunk, chunk.size, out)
        }
        encoder.finish(out)
        val bytes = out.toByteArray()
        // ~2 s at 192 kbps ≈ 48 KB; frames start with the 11-bit sync word.
        assertTrue("only ${bytes.size} bytes", bytes.size in 30_000..70_000)
        val sync = (0 until bytes.size - 1).count { (bytes[it].toInt() and 0xFF) == 0xFF && (bytes[it + 1].toInt() and 0xE0) == 0xE0 }
        assertTrue("no MPEG frames", sync > 50)
    }

    @Test
    fun id3TagHoldsTitleAndArtist() {
        val tag = AudioConvert.id3v2("Breakeven", "The Script")
        assertArrayEquals("ID3".toByteArray(), tag.copyOfRange(0, 3))
        assertEquals(3, tag[3].toInt())
        val size = (tag[6].toInt() shl 21) or (tag[7].toInt() shl 14) or (tag[8].toInt() shl 7) or tag[9].toInt()
        assertEquals(tag.size - 10, size)
        val body = String(tag, Charsets.ISO_8859_1)
        assertTrue(body.contains("TIT2") && body.contains("TPE1"))
        val title = "Breakeven".toByteArray(Charsets.UTF_16LE)
        assertTrue((0..tag.size - title.size).any { i -> title.indices.all { tag[i + it] == title[it] } })
    }
}
