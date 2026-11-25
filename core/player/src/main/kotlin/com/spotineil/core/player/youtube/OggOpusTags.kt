package com.spotineil.core.player.youtube

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the title and artist into an Ogg Opus file (its OpusTags header), so music players show
 * them. Android's muxer leaves the tags empty. Only the tags page is replaced; the audio is untouched.
 */
internal object OggOpusTags {

    /** Copies [src] to [dst] with the given tags. False (and [dst] not written) when [src] isn't laid out as expected. */
    fun write(src: File, dst: File, title: String, artist: String): Boolean {
        val head = ByteArray(minOf(src.length(), 256L * 1024).toInt())
        src.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        val first = page(head, 0) ?: return false
        val second = page(head, first.end) ?: return false
        if (!first.data(head).startsWith("OpusHead") || !second.data(head).startsWith("OpusTags")) return false
        if (second.lacing.last() == 255) return false // the tags go on in another page

        val newPage = buildPage(head, second, tagsPacket(title, artist))
        dst.outputStream().buffered().use { out ->
            out.write(head, 0, first.end)
            out.write(newPage)
            src.inputStream().use { input ->
                input.skip(second.end.toLong())
                input.copyTo(out, 256 * 1024)
            }
        }
        return true
    }

    internal fun tagsPacket(title: String, artist: String): ByteArray {
        val comments = buildList {
            if (title.isNotBlank()) add("TITLE=$title")
            if (artist.isNotBlank()) add("ARTIST=$artist")
        }.map { it.toByteArray(Charsets.UTF_8) }
        val vendor = "Spotineil".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(Charsets.US_ASCII))
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(comments.size))
        comments.forEach { out.write(le32(it.size)); out.write(it) }
        return out.toByteArray()
    }

    private class Page(val start: Int, val headerLength: Int, val lacing: IntArray, val end: Int) {
        fun data(bytes: ByteArray) = bytes.copyOfRange(start + headerLength, end)
    }

    private fun ByteArray.startsWith(text: String) = size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun page(bytes: ByteArray, at: Int): Page? {
        if (at + 27 > bytes.size) return null
        if (!bytes.copyOfRange(at, at + 4).startsWith("OggS")) return null
        val segments = bytes[at + 26].toInt() and 0xFF
        if (segments == 0 || at + 27 + segments > bytes.size) return null
        val lacing = IntArray(segments) { bytes[at + 27 + it].toInt() and 0xFF }
        val end = at + 27 + segments + lacing.sum()
        if (end > bytes.size) return null
        return Page(at, 27 + segments, lacing, end)
    }

    /** The [old] page's header (stream, sequence, granule) around a new packet, with a fresh checksum. */
    private fun buildPage(bytes: ByteArray, old: Page, packet: ByteArray): ByteArray {
        val lacing = ArrayList<Int>()
        var left = packet.size
        while (left >= 255) { lacing += 255; left -= 255 }
        lacing += left
        require(lacing.size <= 255) { "Tags too long" }
        val page = ByteArray(27 + lacing.size + packet.size)
        System.arraycopy(bytes, old.start, page, 0, 26)
        page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0 // checksum, filled in below
        page[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> page[27 + i] = v.toByte() }
        System.arraycopy(packet, 0, page, 27 + lacing.size, packet.size)
        val crc = crc(page)
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        return page
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg's page checksum (CRC-32, polynomial 0x04C11DB7, no reflection, starts at 0). */
    internal fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
