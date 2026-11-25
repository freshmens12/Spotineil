package com.spotineil.core.player.youtube

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OggOpusTagsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun checksumIsOggs() {
        // CRC-32 with polynomial 0x04C11DB7, no reflection, init 0, no final xor: check value of "123456789".
        assertEquals(0x89A1897F.toInt(), OggOpusTags.crc("123456789".toByteArray()))
    }

    private fun page(sequence: Int, granule: Long, packet: ByteArray, type: Int = 0): ByteArray {
        val lacing = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) { lacing += 255; left -= 255 }
        lacing += left
        val b = ByteBuffer.allocate(27 + lacing.size + packet.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule).putInt(0x1234).putInt(sequence).putInt(0)
        b.put(lacing.size.toByte()); lacing.forEach { b.put(it.toByte()) }; b.put(packet)
        val bytes = b.array()
        ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(OggOpusTags.crc(bytes))
        return bytes
    }

    private fun opusHead() = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    @Test
    fun titleAndArtistAreWrittenAndTheAudioIsUntouched() {
        val audio = page(2, 48_000, ByteArray(600) { it.toByte() })
        val src = tmp.newFile("in.opus")
        src.writeBytes(page(0, 0, opusHead(), type = 2) + page(1, 0, OggOpusTags.tagsPacket("", "")) + audio)
        val dst = tmp.newFile("out.opus")

        assertTrue(OggOpusTags.write(src, dst, "Breakeven", "The Script"))

        val out = dst.readBytes()
        val first = page(0, 0, opusHead(), type = 2)
        assertArrayEquals(first, out.copyOfRange(0, first.size))
        val tags = page(1, 0, OggOpusTags.tagsPacket("Breakeven", "The Script"))
        assertArrayEquals(tags, out.copyOfRange(first.size, first.size + tags.size))
        assertArrayEquals(audio, out.copyOfRange(first.size + tags.size, out.size))
        val text = String(tags, Charsets.UTF_8)
        assertTrue(text.contains("TITLE=Breakeven") && text.contains("ARTIST=The Script"))
    }

    @Test
    fun otherFilesAreLeftAlone() {
        val src = tmp.newFile("in.webm")
        src.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(100))
        val dst = File(tmp.root, "out.opus")
        assertFalse(OggOpusTags.write(src, dst, "a", "b"))
        assertFalse(dst.exists())
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray =
        ByteArrayOutputStream().also { it.write(this); it.write(other) }.toByteArray()
}
