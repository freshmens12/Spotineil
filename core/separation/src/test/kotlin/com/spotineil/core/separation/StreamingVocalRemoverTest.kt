package com.spotineil.core.separation

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StreamingVocalRemoverTest {

    /** "Voice" = half of everything, so removing it should leave exactly half. */
    private class HalfModel : VoiceModel {
        var blocks = 0
        override fun voice(left: FloatArray, right: FloatArray, voiceLeft: FloatArray, voiceRight: FloatArray) {
            blocks++
            for (i in left.indices) {
                voiceLeft[i] = left[i] * 0.5f
                voiceRight[i] = right[i] * 0.5f
            }
        }
        override fun close() = Unit
    }

    /** Feeds [input] like the audio pipeline does, waiting for the voice to be ready before reading. */
    private fun runAll(remover: StreamingVocalRemover, input: Array<FloatArray>, channels: Int, waitForVoice: Boolean): Array<FloatArray> {
        val frames = input[0].size
        val out = Array(channels) { FloatArray(frames) }
        val chunk = 2048
        val buf = FloatArray(chunk * channels)
        var w = 0
        var r = 0
        val deadline = System.currentTimeMillis() + 600_000
        while (r < frames) {
            val can = minOf(remover.writable(), frames - w, chunk)
            if (can > 0) {
                for (f in 0 until can) for (c in 0 until channels) buf[f * channels + c] = input[c][w + f]
                remover.write(buf, can)
                w += can
                if (w == frames) remover.endOfStream()
                continue
            }
            if (waitForVoice) {
                val wanted = minOf(r + chunk, frames).toLong()
                val start = System.currentTimeMillis()
                while (remover.readyUntil() < wanted && System.currentTimeMillis() - start < 20_000) Thread.sleep(5)
            }
            val n = remover.read(buf, chunk)
            for (f in 0 until n) for (c in 0 until channels) out[c][r + f] = buf[f * channels + c]
            r += n
            check(System.currentTimeMillis() < deadline)
        }
        return out
    }

    @Test
    fun removesTheVoiceSeamlesslyAcrossBlocks() {
        val model = HalfModel()
        val remover = StreamingVocalRemover({ model })
        remover.configure(44_100, 2)
        remover.armed = true
        remover.removeVoice = true
        val frames = 44_100 * 40 // 40 s → several blocks
        val input = Array(2) { c -> FloatArray(frames) { (0.5 * sin(2 * PI * (440 + c * 110) * it / 44_100)).toFloat() } }
        val out = runAll(remover, input, 2, waitForVoice = true)
        remover.release()
        assertTrue("blocks ${model.blocks}", model.blocks >= 3)
        // After the first quarter second of fade-in, every sample is exactly half (no seams between blocks).
        var worst = 0.0
        for (i in 44_100 until frames - 44_100) for (c in 0..1) worst = maxOf(worst, abs(out[c][i] - input[c][i] * 0.5).toDouble())
        assertTrue("worst deviation $worst", worst < 1e-3)
    }

    @Test
    fun voiceOnPassesTheSongThroughUnchanged() {
        val remover = StreamingVocalRemover({ HalfModel() })
        remover.configure(48_000, 2)
        val input = Array(2) { FloatArray(48_000) { i -> sin(i * 0.01).toFloat() } }
        val out = runAll(remover, input, 2, waitForVoice = false)
        for (i in input[0].indices) assertEquals(input[0][i], out[0][i], 0f)
    }

    @Test
    fun resampledStreamsLineUpWithTheOriginal() {
        // At 48 kHz the audio goes to 44.1 kHz and the voice comes back: "half" must still line up.
        val model = HalfModel()
        val remover = StreamingVocalRemover({ model })
        remover.configure(48_000, 2)
        remover.armed = true
        remover.removeVoice = true
        val frames = 48_000 * 30
        val input = Array(2) { FloatArray(frames) { i -> (0.5 * sin(2 * PI * 1_000 * i / 48_000)).toFloat() } }
        val out = runAll(remover, input, 2, waitForVoice = true)
        remover.release()
        var err = 0.0
        var sig = 0.0
        for (i in 48_000 until frames - 48_000) {
            val d = out[0][i] - input[0][i] * 0.5
            err += d * d
            sig += (input[0][i] * 0.5) * (input[0][i] * 0.5)
        }
        assertTrue("relative error ${kotlin.math.sqrt(err / sig)}", kotlin.math.sqrt(err / sig) < 0.02)
    }

    /**
     * Writes "music only" versions of real songs with the real model (for listening), at 44.1 kHz and
     * at 48 kHz (how YouTube Music streams decode). Runs only when SPLEETER_DIR and SAMPLES_DIR are set.
     */
    @Test
    fun separateSampleSongs() {
        val modelDir = System.getenv("SPLEETER_DIR")?.let(::File)
        val samples = System.getenv("SAMPLES_DIR")?.let(::File)
        assumeTrue(modelDir?.isDirectory == true && samples?.isDirectory == true)
        val vocals = modelDir!!.listFiles()!!.first { it.name.startsWith("vocals") && it.name.endsWith(".onnx") }
        val accompaniment = modelDir.listFiles()!!.first { it.name.startsWith("accompaniment") && it.name.endsWith(".onnx") }
        for (wav in samples!!.listFiles { f -> f.name.endsWith(".wav") && !f.name.contains("-music-only") }!!) {
            val (rate, song) = readWav(wav)
            for (target in listOf(rate, 48_000).distinct()) {
                val input = if (target == rate) song else Array(2) { c ->
                    FloatArray((song[c].size.toLong() * target / rate).toInt()).also {
                        SincInterpolator().resample(song[c], rate, 0.0, it, target, 0.0)
                    }
                }
                var model: SpleeterVoiceModel? = null
                val remover = StreamingVocalRemover({ SpleeterVoiceModel(vocals, accompaniment, threads = 2).also { model = it } })
                remover.configure(target, 2)
                remover.armed = true
                remover.removeVoice = true
                val t0 = System.nanoTime()
                val out = runAll(remover, input, 2, waitForVoice = true)
                val secs = (System.nanoTime() - t0) / 1e9
                remover.release()
                val name = wav.nameWithoutExtension + "-music-only-${target / 1000.0}kHz.wav"
                writeWav(File(samples, name), target, out)
                println("$name: ${"%.1f".format(input[0].size.toDouble() / target)} s of song in ${"%.1f".format(secs)} s")
            }
        }
    }

    private fun readWav(file: File): Pair<Int, Array<FloatArray>> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 44_100
        var channels = 2
        while (pos < bytes.limit() - 8) {
            val id = String(ByteArray(4) { bytes.get(pos + it) })
            val size = bytes.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bytes.getShort(pos + 10).toInt()
                rate = bytes.getInt(pos + 12)
            }
            if (id == "data") {
                val frames = size / (2 * channels)
                val out = Array(2) { FloatArray(frames) }
                for (f in 0 until frames) for (c in 0..1) {
                    out[c][f] = bytes.getShort(pos + 8 + (f * channels + minOf(c, channels - 1)) * 2) / 32768f
                }
                return rate to out
            }
            pos += 8 + size
        }
        error("no data in $file")
    }

    private fun writeWav(file: File, rate: Int, ch: Array<FloatArray>) {
        val frames = ch[0].size
        val buf = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        buf.put("data".toByteArray()).putInt(frames * 4)
        for (f in 0 until frames) for (c in 0..1) buf.putShort((ch[c][f] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(buf.array()) }
    }
}
