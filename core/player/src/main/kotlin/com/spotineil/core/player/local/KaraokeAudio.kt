package com.spotineil.core.player.local

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.spotineil.core.player.VoiceRemoval
import com.spotineil.core.separation.SpleeterVoiceModel
import com.spotineil.core.separation.StreamingVocalRemover
import com.spotineil.core.separation.VoiceModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The karaoke "vocals off" switch, shared by the controller (UI side) and the audio processor inside
 * the player. Everything runs in the app's one process.
 */
internal object KaraokeAudio {
    private val _state = MutableStateFlow<VoiceRemoval>(VoiceRemoval.Off)
    val state: StateFlow<VoiceRemoval> = _state.asStateFlow()

    @Volatile var armed = false
        private set

    @Volatile var removeVoice = false
        private set

    /** Loads the voice model (on the separation thread); null when it isn't downloaded. */
    @Volatile var loadModel: () -> VoiceModel? = { null }

    private val removers = CopyOnWriteArrayList<StreamingVocalRemover>()

    fun register(remover: StreamingVocalRemover) {
        removers += remover
        remover.armed = armed
        remover.removeVoice = removeVoice
    }

    fun unregister(remover: StreamingVocalRemover) {
        removers -= remover
    }

    /** Karaoke mode is open: let the player read ahead so the voice can be separated in time. */
    fun setArmed(value: Boolean) {
        armed = value
        removers.forEach { it.armed = value }
        if (!value) setRemoveVoice(false)
    }

    fun setRemoveVoice(value: Boolean) {
        removeVoice = value
        if (value) _state.value = VoiceRemoval.Preparing
        removers.forEach { it.removeVoice = value }
        if (!value) _state.value = VoiceRemoval.Off
    }

    fun setState(value: VoiceRemoval) {
        _state.value = value
    }

    /** From the audio side. */
    fun onStatus(status: StreamingVocalRemover.Status) {
        if (!removeVoice) return
        _state.value = when (status) {
            StreamingVocalRemover.Status.Off -> VoiceRemoval.Off
            StreamingVocalRemover.Status.Preparing -> VoiceRemoval.Preparing
            StreamingVocalRemover.Status.Removing -> VoiceRemoval.Removing
            StreamingVocalRemover.Status.Unavailable -> VoiceRemoval.Failed
        }
    }
}

/**
 * Media3 audio processor around [StreamingVocalRemover]: PCM in, PCM out (same format), with the
 * singing voice taken out while karaoke "vocals off" is on.
 *
 * The voice is separated in ~12 s blocks, so the processor needs to hold audio well ahead of what is
 * heard. Media3's sink only gives a processor more input after its last output went to the
 * speaker, so the lookahead is built once: when vocals go off (and after a seek while they're off)
 * the processor holds the sound back for a moment, like buffering, while the decoder fills
 * [LOOKAHEAD_S] of audio. From then on input and output move in step, so that lookahead stays put
 * for the rest of the listening (songs that follow each other included). With vocals on nothing is
 * held back.
 */
@OptIn(UnstableApi::class)
internal class KaraokeAudioProcessor(
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioProcessor {
    private val remover = StreamingVocalRemover(
        loadModel = { KaraokeAudio.loadModel() },
        lookaheadSeconds = LOOKAHEAD_S + 5.0,
        onStatus = KaraokeAudio::onStatus,
    )
    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var samples = FloatArray(0)
    private var registered = false

    /** The lookahead has been built since the last flush. */
    private var filled = false
    private var fillStartNs = 0L
    /** While vocals are off: frames that may be handed on (as many as came in, so the lookahead stays). */
    private var owed = 0L

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.channelCount in 1..2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        pendingFormat = if (supported) inputAudioFormat else AudioFormat.NOT_SET
        return pendingFormat
    }

    override fun isActive(): Boolean = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || format == AudioFormat.NOT_SET) return
        val frames = minOf(inputBuffer.remaining() / format.bytesPerFrame, remover.writable())
        if (frames <= 0) return
        val n = frames * format.channelCount
        if (samples.size < n) samples = FloatArray(n)
        if (format.encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until n) samples[i] = inputBuffer.getFloat()
        } else {
            for (i in 0 until n) samples[i] = inputBuffer.getShort() / 32768f
        }
        remover.write(samples, frames)
        owed += frames
    }

    override fun queueEndOfStream() {
        inputEnded = true
        remover.endOfStream()
    }

    override fun getOutput(): ByteBuffer {
        if (outputBuffer.hasRemaining() || format == AudioFormat.NOT_SET) return outputBuffer
        val readable = remover.readable()
        if (readable == 0) return AudioProcessor.EMPTY_BUFFER
        val vocalsOff = remover.removeVoice && remover.armed && !inputEnded
        val maxFrames = if (!vocalsOff) {
            filled = false
            fillStartNs = 0L
        owed = 0L
            minOf(readable, CHUNK_FRAMES)
        } else {
            if (!filled) {
                // Building the lookahead: hold the sound back (a short pause) while the decoder runs
                // ahead, and until the voice is found in what plays next, so the music comes back
                // without it.
                if (fillStartNs == 0L) fillStartNs = nanoTime()
                val filling = readable < LOOKAHEAD_S * format.sampleRate && remover.writable() > 0
                val waitingForVoice = !remover.voiceReadyOrUnavailable() && nanoTime() - fillStartNs < MAX_HOLD_NS
                if (filling || waitingForVoice) return AudioProcessor.EMPTY_BUFFER
                filled = true
                owed = (RESTART_S * format.sampleRate).toLong() // restart the speaker after the pause
            }
            // From now on: hand on as much as comes in, so the lookahead stays where it is.
            minOf(readable.toLong(), owed, CHUNK_FRAMES.toLong()).toInt()
        }
        if (maxFrames <= 0) return AudioProcessor.EMPTY_BUFFER

        val channels = format.channelCount
        if (samples.size < maxFrames * channels) samples = FloatArray(maxFrames * channels)
        val frames = remover.read(samples, maxFrames)
        owed = (owed - frames).coerceAtLeast(0L)
        val bytes = frames * format.bytesPerFrame
        if (buffer.capacity() < bytes) buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        buffer.clear()
        val n = frames * channels
        if (format.encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until n) buffer.putFloat(samples[i])
        } else {
            for (i in 0 until n) buffer.putShort((samples[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        }
        buffer.flip()
        outputBuffer = buffer
        return outputBuffer
    }

    override fun isEnded(): Boolean = inputEnded && remover.isDrained() && !outputBuffer.hasRemaining()

    /** Frames taken in but not yet handed on (the lookahead). For tests. */
    internal fun bufferedFrames(): Int = remover.readable()

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        filled = false
        fillStartNs = 0L
        if (pendingFormat != format) {
            format = pendingFormat
            if (format != AudioFormat.NOT_SET) remover.configure(format.sampleRate, format.channelCount)
        } else {
            remover.flush()
        }
        if (!registered) {
            KaraokeAudio.register(remover)
            registered = true
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        format = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        KaraokeAudio.unregister(remover)
        registered = false
        remover.release()
    }

    private companion object {
        const val CHUNK_FRAMES = 4096
        /** Audio held ahead of what's heard while vocals are off: one ~12 s block plus time to separate it. */
        const val LOOKAHEAD_S = 18
        /** Never hold the sound back longer than this (a very slow phone just keeps the voice at first). */
        const val MAX_HOLD_NS = 10_000_000_000L
        /** Sound handed on at once when playback resumes after the pause (refills the speaker). */
        const val RESTART_S = 0.5
    }
}

/**
 * The voice model files (Spleeter 2-stems, fp16 ONNX, ~39 MB), downloaded once to the app's private
 * storage when karaoke "vocals off" is first used.
 */
internal class VoiceModelStore(context: Context) {
    private class ModelFile(val name: String, val size: Long, val sha256: String)

    private val dir = File(context.filesDir, "karaoke/spleeter-2stems-fp16")
    private val files = listOf(
        ModelFile("vocals.fp16.onnx", 19_681_017, "24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668"),
        ModelFile("accompaniment.fp16.onnx", 19_681_024, "d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0"),
    )

    fun installed(): Boolean = files.all { File(dir, it.name).length() == it.size }

    fun load(): VoiceModel? {
        if (!installed()) return null
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        return SpleeterVoiceModel(File(dir, files[0].name), File(dir, files[1].name), threads)
    }

    /** Downloads the model, reporting 0..1. Blocking: call from a background thread. */
    fun download(onProgress: (Float) -> Unit) {
        dir.mkdirs()
        val total = files.sumOf { it.size }.toFloat()
        var done = 0L
        for (file in files) {
            val target = File(dir, file.name)
            if (target.length() == file.size) {
                done += file.size
                continue
            }
            val part = File(dir, file.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL("$BASE_URL/${file.name}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress((done / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != file.size || hash != file.sha256) {
                part.delete()
                error("The voice model download was damaged")
            }
            check(part.renameTo(target)) { "Could not save the voice model" }
        }
    }

    private companion object {
        /** The model files are published with Spotineil (MIT license from Deezer's Spleeter). */
        const val BASE_URL = "https://github.com/freshmens12/Spotineil/releases/download/karaoke-model-1"
    }
}
