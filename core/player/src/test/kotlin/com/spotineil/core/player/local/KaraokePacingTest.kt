package com.spotineil.core.player.local

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the karaoke processor inside Media3's real processing pipeline, fed the way DefaultAudioSink
 * feeds it (input only after the last output fit into the AudioTrack), with a 250 ms AudioTrack that
 * plays in real time. Checks that the read-ahead karaoke needs builds up without the sound ever
 * stopping, whatever the vocals switch does.
 */
class KaraokePacingTest {

    private val rate = 48_000
    private val processor = KaraokeAudioProcessor()
    private val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))

    // simulated DefaultAudioSink + AudioTrack
    private val trackCapacity = rate / 4
    private var trackFill = 0
    private var outputBuffer: ByteBuffer? = null
    private var inputBuffer: ByteBuffer? = null

    @After
    fun tearDown() {
        KaraokeAudio.setArmed(false)
        pipeline.reset()
    }

    private fun drainOutput() {
        val ob = outputBuffer ?: return
        val can = minOf(ob.remaining() / 4, trackCapacity - trackFill)
        ob.position(ob.position() + can * 4)
        trackFill += can
        if (!ob.hasRemaining()) outputBuffer = null
    }

    private fun processBuffers() {
        drainOutput()
        if (outputBuffer != null) return
        while (!pipeline.isEnded) {
            while (true) {
                val b = pipeline.output
                if (!b.hasRemaining()) break
                outputBuffer = b
                drainOutput()
                if (outputBuffer != null) return
            }
            val input = inputBuffer
            if (input == null || !input.hasRemaining()) return
            pipeline.queueInput(input)
        }
    }

    private fun handleBuffer(buffer: ByteBuffer): Boolean {
        if (inputBuffer == null) inputBuffer = buffer
        processBuffers()
        if (inputBuffer?.hasRemaining() == false) {
            inputBuffer = null
            return true
        }
        return false
    }

    private fun decoderBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(960 * 4).order(ByteOrder.nativeOrder()).also { b ->
            repeat(960 * 2) { b.putShort((it % 200).toShort()) }
            b.flip()
        }

    /**
     * Plays [seconds] in 10 ms ticks, the decoder able to supply [decoderBuffersPerTick] buffers of
     * 20 ms per tick. Returns the ticks in which the AudioTrack ran dry (after the first 0.5 s).
     */
    private fun play(seconds: Int, decoderBuffersPerTick: Int = 200): Int {
        var underruns = 0
        var pending: ByteBuffer? = null
        repeat(seconds * 100) { tick ->
            val drained = minOf(trackFill, rate / 100)
            if (drained < rate / 100 && tick > 50) underruns++
            trackFill -= drained
            for (i in 0 until decoderBuffersPerTick) {
                val buf = pending ?: decoderBuffer()
                if (handleBuffer(buf)) pending = null else {
                    pending = buf
                    break
                }
            }
        }
        return underruns
    }

    private fun start() {
        pipeline.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush()
    }

    private fun seek() {
        pipeline.flush()
        trackFill = 0
        outputBuffer = null
        inputBuffer = null
    }

    @Test
    fun openingKaraokeReadsAheadWithoutStoppingTheMusic() {
        start()
        KaraokeAudio.setArmed(true) // karaoke mode open, vocals on
        assertEquals(0, play(seconds = 10))
        val ahead = processor.bufferedFrames().toDouble() / rate
        assertTrue("lookahead only $ahead s", ahead >= 17.5)
        assertEquals("dropouts once it is built", 0, play(seconds = 10))
        assertTrue(processor.bufferedFrames().toDouble() / rate >= 17.5)
    }

    @Test
    fun turningVocalsOffNeverStopsTheMusic() {
        start()
        KaraokeAudio.setArmed(true)
        assertEquals(0, play(seconds = 3))
        KaraokeAudio.setRemoveVoice(true) // no model in tests: the original just keeps playing
        assertEquals(0, play(seconds = 15))
        assertTrue(processor.bufferedFrames().toDouble() / rate >= 17.5)
    }

    @Test
    fun aSeekWithVocalsOffNeverStopsTheMusic() {
        start()
        KaraokeAudio.setArmed(true)
        KaraokeAudio.setRemoveVoice(true)
        assertEquals(0, play(seconds = 5))
        seek()
        assertEquals(0, play(seconds = 10))
        assertTrue(processor.bufferedFrames() > rate * 15)
    }

    @Test
    fun aDecoderOnlySixTimesFasterThanPlaybackStillKeepsUp() {
        start()
        KaraokeAudio.setArmed(true)
        KaraokeAudio.setRemoveVoice(true)
        assertEquals(0, play(seconds = 20, decoderBuffersPerTick = 3))
        assertTrue(processor.bufferedFrames().toDouble() / rate >= 17.5)
    }

    @Test
    fun closingKaraokePlaysTheReadAheadOutSmoothly() {
        start()
        KaraokeAudio.setArmed(true)
        play(seconds = 10)
        KaraokeAudio.setArmed(false)
        assertEquals(0, play(seconds = 25))
        assertTrue(processor.bufferedFrames() <= 4096)
    }

    @Test
    fun outsideKaraokeModeNothingIsHeldBack() {
        start()
        assertEquals(0, play(seconds = 3))
        assertTrue("buffered ${processor.bufferedFrames()}", processor.bufferedFrames() <= 4096)
    }
}
