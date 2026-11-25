package com.spotineil.core.player.youtube

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turning a downloaded stream into MP3 or M4A (AAC). Both decode it first; nothing here touches the network. */
internal object AudioConvert {

    class PcmFormat(val sampleRate: Int, val channels: Int)

    /** The first audio track's MIME type (e.g. audio/opus), or null. */
    fun audioMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("audio/") }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes [src] to 16-bit PCM. [onFormat] comes first, then [onPcm] with interleaved samples
     * (the array is reused: copy what you keep). [checkCancelled] runs between buffers.
     */
    fun decode(src: File, checkCancelled: () -> Unit, onFormat: (PcmFormat) -> Unit, onPcm: (ShortArray, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(src.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var announced = false
            var floatOut = false
            var shorts = ShortArray(0)
            while (true) {
                checkCancelled()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = c.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && !announced)) {
                    val f = c.outputFormat
                    floatOut = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    onFormat(PcmFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)))
                    announced = true
                }
                if (o >= 0) {
                    if (info.size > 0) {
                        val buffer = c.getOutputBuffer(o)!!.apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            order(ByteOrder.nativeOrder())
                        }
                        if (floatOut) {
                            val fb = buffer.asFloatBuffer()
                            val n = fb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            for (k in 0 until n) shorts[k] = (fb.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            onPcm(shorts, n)
                        } else {
                            val sb = buffer.asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            onPcm(shorts, n)
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    // ------------------------------------------------------------------------------------ MP3

    /** [src] → MP3 at [bitrateKbps] in [dst], with an ID3 tag (title, artist) in front. */
    fun toMp3(src: File, dst: File, title: String, artist: String, checkCancelled: () -> Unit, bitrateKbps: Int = 192) {
        dst.outputStream().buffered(256 * 1024).use { out ->
            out.write(id3v2(title, artist))
            var encoder: Mp3Encoder? = null
            decode(
                src, checkCancelled,
                onFormat = { f -> if (encoder == null) encoder = Mp3Encoder(f.channels, f.sampleRate, bitrateKbps) },
                onPcm = { pcm, n -> encoder!!.encode(pcm, n, out) },
            )
            (encoder ?: throw IOException("No audio")).finish(out)
        }
    }

    /** The LAME encoder (pure Java port). */
    internal class Mp3Encoder(private val channels: Int, sampleRate: Int, bitrateKbps: Int) {
        private val lame = Lame()
        private val gfp: LameGlobalFlags
        private var left = IntArray(0)
        private var right = IntArray(0)
        private var mp3 = ByteArray(0)

        init {
            require(channels in 1..2) { "Unsupported channel count $channels" }
            val ga = GainAnalysis()
            val bs = BitStream()
            val presets = Presets()
            val qupvt = QuantizePVT()
            val qu = Quantize()
            val vbr = VBRTag()
            val ver = Version()
            val id3 = ID3Tag()
            val rv = Reservoir()
            val tak = Takehiro()
            val parse = Parse()
            val mpg = MPGLib()
            val intf = Interface()
            val common = Common()
            lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            presets.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            parse.setModules(ver, id3, presets)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)

            gfp = lame.lame_init()
            gfp.num_channels = channels
            gfp.in_samplerate = sampleRate
            gfp.brate = bitrateKbps
            gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            gfp.quality = 7 // LAME "fast" mode: about twice as quick, no audible difference at 192 kbps
            id3.id3tag_init(gfp)
            gfp.write_id3tag_automatic = false
            gfp.findReplayGain = false
            check(lame.lame_init_params(gfp) >= 0) { "MP3 encoder settings not supported" }
        }

        fun encode(pcm: ShortArray, samples: Int, out: OutputStream) {
            val frames = samples / channels
            if (left.size < frames) { left = IntArray(frames); right = IntArray(frames) }
            for (i in 0 until frames) {
                val l = pcm[i * channels].toInt() shl 16
                left[i] = l
                right[i] = if (channels == 2) pcm[i * channels + 1].toInt() shl 16 else l
            }
            val need = (1.25 * frames + 7200).toInt()
            if (mp3.size < need) mp3 = ByteArray(need)
            val n = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3, 0, mp3.size)
            if (n < 0) throw IOException("MP3 encoding failed ($n)")
            out.write(mp3, 0, n)
        }

        fun finish(out: OutputStream) {
            if (mp3.size < 7200) mp3 = ByteArray(7200)
            val n = lame.lame_encode_flush(gfp, mp3, 0, mp3.size)
            if (n > 0) out.write(mp3, 0, n)
        }
    }

    /** An ID3v2.3 tag with the title (TIT2) and artist (TPE1), in UTF-16 so every language shows. */
    fun id3v2(title: String, artist: String): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, text: String) {
            if (text.isBlank()) return
            val body = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            frames.write(id.toByteArray(Charsets.US_ASCII))
            frames.write(ByteBuffer.allocate(4).putInt(body.size).array())
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        frame("TIT2", title)
        frame("TPE1", artist)
        val size = frames.size()
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        return header + frames.toByteArray()
    }

    // ------------------------------------------------------------------------------------ M4A

    /** [src] → AAC-LC at [bitrate] in an .m4a file [dst], with Android's own encoder. */
    fun toM4a(src: File, dst: File, checkCancelled: () -> Unit, bitrate: Int = 256_000) {
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var encoder: MediaCodec? = null
        var track = -1
        var sampleRate = 48_000
        var channels = 2
        var framesIn = 0L
        val info = MediaCodec.BufferInfo()
        var ended = false

        fun drain(untilEnd: Boolean) {
            val e = encoder ?: return
            while (true) {
                val o = e.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(e.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buffer = e.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        e.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            ended = true
                            return
                        }
                    }
                }
            }
        }

        fun queue(bytes: ByteArray, length: Int, endOfStream: Boolean) {
            val e = encoder ?: return
            var offset = 0
            while (offset < length || endOfStream) {
                checkCancelled()
                val i = e.dequeueInputBuffer(10_000)
                if (i < 0) {
                    drain(untilEnd = false)
                    continue
                }
                val buffer = e.getInputBuffer(i)!!
                buffer.clear()
                val n = minOf(buffer.remaining() / (2 * channels) * (2 * channels), length - offset)
                buffer.put(bytes, offset, n)
                val pts = framesIn * 1_000_000L / sampleRate
                framesIn += n / (2 * channels)
                offset += n
                val last = endOfStream && offset >= length
                e.queueInputBuffer(i, 0, n, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(untilEnd = false)
                if (last) return
            }
        }

        try {
            var bytes = ByteArray(0)
            decode(
                src, checkCancelled,
                onFormat = { f ->
                    if (encoder == null) {
                        sampleRate = f.sampleRate
                        channels = f.channels
                        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                        }
                        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            start()
                        }
                    }
                },
                onPcm = { pcm, n ->
                    if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, n)
                    queue(bytes, n * 2, endOfStream = false)
                },
            )
            if (encoder == null) throw IOException("No audio")
            queue(ByteArray(0), 0, endOfStream = true)
            while (!ended) drain(untilEnd = true)
            muxer.stop()
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer.release() }
        }
    }
}
