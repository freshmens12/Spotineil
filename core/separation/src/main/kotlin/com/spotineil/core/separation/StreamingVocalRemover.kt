package com.spotineil.core.separation

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Removes the singing voice from a stream of audio while it plays.
 *
 * The audio pipeline [write]s decoded audio in and [read]s audio out. While [armed] (karaoke mode
 * is open) up to [lookaheadSeconds] are accepted ahead of what is being played, so a background
 * thread can separate ~12 s blocks before they're heard. While [removeVoice] is on, each output
 * sample is the original minus the voice the model found in it; where no block is ready yet (just
 * after switching on or seeking, or on a phone too slow to keep up) the original plays instead, so
 * playback never stalls and stays in sync.
 *
 * Blocks overlap: the edges of each block are dropped and neighbours are cross-faded.
 *
 * Thread-safety: [write], [read], [flush], [endOfStream], [configure] come from the audio thread;
 * [armed] and [removeVoice] from anywhere; the separation runs on its own thread.
 */
class StreamingVocalRemover(
    private val loadModel: () -> VoiceModel?,
    private val lookaheadSeconds: Double = 25.0,
    private val onStatus: (Status) -> Unit = {},
) {
    enum class Status { Off, Preparing, Removing, Unavailable }

    private val lock = Object()

    @Volatile var armed: Boolean = false
        set(value) {
            field = value
            synchronized(lock) { lock.notifyAll() }
        }

    @Volatile var removeVoice: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            synchronized(lock) {
                if (value) startBlocksAt = readPos - edge
                lock.notifyAll()
            }
            ensureWorker()
            report()
        }

    // ---- stream state (guarded by lock) ----
    private var rate = 44_100
    private var channels = 2
    private var input = Ring(0, 0)
    private var voice = Ring(0, 0)
    private var written = 0L // frames written so far (absolute index of the next one)
    private var readPos = 0L // next frame to output
    private var ended = false
    private var generation = 0

    // block scheduling
    private var blockLen = 0 // one model block, in stream frames
    private var edge = 0 // dropped at each block end
    private var fade = 0 // cross-fade between blocks
    private var startBlocksAt = Long.MIN_VALUE // where the next run of blocks starts (after a restart)
    private var nextBlock = Long.MIN_VALUE // start of the next block to separate
    private var voiceFrom = 0L // the voice is known for [voiceFrom, voiceReady)
    private var voiceReady = 0L
    private var voiceWrittenTo = 0L // end of the last block written (its tail is cross-faded with the next)

    // output gain: 0 = original, 1 = voice removed
    private var gain = 0f
    private var gainStep = 0f

    private var worker: Thread? = null
    private var modelFailed = false
    private var lastStatus: Status? = null

    fun configure(sampleRate: Int, channelCount: Int) = synchronized(lock) {
        require(channelCount == 1 || channelCount == 2)
        rate = sampleRate
        channels = channelCount
        val capacity = ((lookaheadSeconds + 30) * sampleRate).toInt() // lookahead + one block + history
        input = Ring(channelCount, capacity)
        voice = Ring(channelCount, capacity)
        blockLen = ceil(VoiceModel.BLOCK_SAMPLES.toDouble() * sampleRate / VoiceModel.SAMPLE_RATE).toInt()
        edge = (0.4 * sampleRate).toInt()
        fade = (0.3 * sampleRate).toInt()
        gainStep = 1f / (0.25f * sampleRate)
        resetLocked()
    }

    /** After a seek: drops everything buffered. */
    fun flush() = synchronized(lock) { resetLocked() }

    fun endOfStream() = synchronized(lock) {
        ended = true
        lock.notifyAll()
    }

    /** Frames that may be written now. */
    fun writable(): Int = synchronized(lock) {
        val ahead = written - readPos
        val limit = if (armed) (lookaheadSeconds * rate).toLong() else SMALL_BUFFER.toLong()
        max(0L, limit - ahead).toInt().coerceAtMost(input.capacity - (written - input.start).toInt())
    }

    /** Appends [frames] interleaved frames from [src]. Call [writable] first. */
    fun write(src: FloatArray, frames: Int) = synchronized(lock) {
        input.ensureFrom(readPos - edge - SINC_MARGIN * 4) // keep a little history for the next block
        input.write(written, src, frames, channels)
        written += frames
        lock.notifyAll()
    }

    /** Frames that [read] can return now. */
    fun readable(): Int = synchronized(lock) { (written - readPos).toInt() }

    fun isDrained(): Boolean = synchronized(lock) { ended && readPos >= written }

    /** Writes up to [maxFrames] interleaved output frames to [dst]; returns how many. */
    fun read(dst: FloatArray, maxFrames: Int): Int {
        val n: Int
        synchronized(lock) {
            n = min(maxFrames.toLong(), written - readPos).toInt()
            val remove = removeVoice
            for (f in 0 until n) {
                val i = readPos + f
                val known = i >= voiceFrom && i < voiceReady
                val target = if (remove && known) 1f else 0f
                gain = if (!known) 0f else if (gain < target) min(target, gain + gainStep) else max(target, gain - gainStep)
                for (c in 0 until channels) {
                    val x = input.get(i, c)
                    dst[f * channels + c] = if (gain > 0f) x - gain * voice.get(i, c) else x
                }
            }
            readPos += n
            lock.notifyAll()
        }
        report()
        return n
    }

    /** The voice is known for what plays next (it is being removed now), or it never will be (no model). */
    fun voiceReadyOrUnavailable(): Boolean = synchronized(lock) { modelFailed || readPos in voiceFrom until voiceReady }

    /** The voice is known (and removed, while [removeVoice]) up to this frame. For tests. */
    internal fun readyUntil(): Long = synchronized(lock) { voiceReady }

    internal fun position(): Long = synchronized(lock) { readPos }

    /** Stops the separation thread (the model is closed). */
    fun release() {
        synchronized(lock) {
            generation++
            worker?.interrupt()
            worker = null
            lock.notifyAll()
        }
    }

    private fun resetLocked() {
        generation++
        written = 0L
        readPos = 0L
        ended = false
        input.start = 0L
        voice.start = 0L
        voiceFrom = 0L
        voiceReady = 0L
        voiceWrittenTo = 0L
        gain = 0f
        startBlocksAt = -edge.toLong()
        nextBlock = Long.MIN_VALUE
        lock.notifyAll()
    }

    private fun report() {
        val status = synchronized(lock) {
            when {
                !removeVoice -> Status.Off
                modelFailed -> Status.Unavailable
                readPos in voiceFrom until voiceReady -> Status.Removing
                else -> Status.Preparing
            }
        }
        if (status != lastStatus) {
            lastStatus = status
            onStatus(status)
        }
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker != null || !removeVoice) return
            worker = Thread(::workLoop, "karaoke-voice").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    // ---------------------------------------------------------------- separation thread

    private fun workLoop() {
        var model: VoiceModel? = null
        try {
            model = loadModel()
            if (model == null) {
                synchronized(lock) { modelFailed = true }
                report()
                return
            }
            synchronized(lock) { modelFailed = false }
            val m44Left = FloatArray(VoiceModel.BLOCK_SAMPLES)
            val m44Right = FloatArray(VoiceModel.BLOCK_SAMPLES)
            val v44Left = FloatArray(VoiceModel.BLOCK_SAMPLES)
            val v44Right = FloatArray(VoiceModel.BLOCK_SAMPLES)
            val sinc = SincInterpolator()
            while (!Thread.currentThread().isInterrupted) {
                // Wait for a block's worth of audio (or the end of the stream).
                val job = synchronized(lock) {
                    while (true) {
                        if (worker != Thread.currentThread()) return
                        if (removeVoice && armed) {
                            if (startBlocksAt != Long.MIN_VALUE) {
                                nextBlock = max(startBlocksAt, readPos - edge)
                                voiceFrom = nextBlock + edge
                                voiceReady = voiceFrom
                                voiceWrittenTo = voiceFrom
                                startBlocksAt = Long.MIN_VALUE
                            }
                            // Fallen behind playback (slow phone, or a long pause in work): skip ahead.
                            if (nextBlock + blockLen - edge - fade <= readPos) {
                                nextBlock = readPos - edge
                                voiceFrom = nextBlock + edge
                                voiceReady = voiceFrom
                                voiceWrittenTo = voiceFrom
                            }
                            val need = nextBlock + blockLen + SINC_MARGIN
                            if (nextBlock < written && (written >= need || ended)) break
                        }
                        lock.wait(500)
                    }
                    val start = nextBlock - SINC_MARGIN
                    val len = blockLen + 2 * SINC_MARGIN
                    val copy = Array(channels) { c -> FloatArray(len) { input.getOrZero(start + it, c, written) } }
                    val endsAt = if (ended && nextBlock + blockLen >= written) written else -1L
                    Job(generation, nextBlock, copy, endsAt)
                }

                // Separate (no lock held: playback keeps going meanwhile).
                val srcStart = (job.start - SINC_MARGIN).toDouble() / rate
                val blockStart = job.start.toDouble() / rate
                val left = job.samples[0]
                val right = job.samples[if (channels == 2) 1 else 0]
                if (rate == VoiceModel.SAMPLE_RATE) {
                    System.arraycopy(left, SINC_MARGIN, m44Left, 0, VoiceModel.BLOCK_SAMPLES)
                    System.arraycopy(right, SINC_MARGIN, m44Right, 0, VoiceModel.BLOCK_SAMPLES)
                } else {
                    sinc.resample(left, rate, srcStart, m44Left, VoiceModel.SAMPLE_RATE, blockStart)
                    sinc.resample(right, rate, srcStart, m44Right, VoiceModel.SAMPLE_RATE, blockStart)
                }
                model.voice(m44Left, m44Right, v44Left, v44Right)

                val from = job.start + edge
                val to = if (job.endsAt >= 0) max(from, job.endsAt) else job.start + blockLen - edge
                val count = (to - from).toInt()
                val voiceOut = Array(channels) { FloatArray(count) }
                val fromSec = from.toDouble() / rate
                for (c in 0 until channels) {
                    val src = if (channels == 1) mono(v44Left, v44Right) else if (c == 0) v44Left else v44Right
                    if (rate == VoiceModel.SAMPLE_RATE) {
                        System.arraycopy(src, edge, voiceOut[c], 0, count)
                    } else {
                        sinc.resample(src, VoiceModel.SAMPLE_RATE, blockStart, voiceOut[c], rate, fromSec)
                    }
                }

                synchronized(lock) {
                    if (job.generation != generation || job.start != nextBlock) return@synchronized
                    voice.ensureFrom(readPos - edge - SINC_MARGIN * 4)
                    // The previous block's tail overlaps this block's head: cross-fade there.
                    val blendUntil = if (voiceWrittenTo > from && from >= voiceFrom) min(voiceWrittenTo, to) else from
                    for (c in 0 until channels) {
                        for (k in 0 until count) {
                            val i = from + k
                            val v = voiceOut[c][k]
                            if (i < blendUntil) {
                                val w = (i - from).toFloat() / (blendUntil - from)
                                voice.set(i, c, voice.get(i, c) * (1 - w) + v * w)
                            } else {
                                voice.set(i, c, v)
                            }
                        }
                    }
                    val last = job.endsAt >= 0
                    voiceReady = if (last) to else to - fade
                    voiceWrittenTo = to
                    nextBlock = if (last) Long.MAX_VALUE / 2 else job.start + blockLen - 2 * edge - fade
                }
                report()
            }
        } catch (_: InterruptedException) {
            // released
        } catch (t: Throwable) {
            synchronized(lock) { modelFailed = true }
            report()
        } finally {
            runCatching { model?.close() }
            synchronized(lock) { if (worker == Thread.currentThread()) worker = null }
        }
    }

    private fun mono(l: FloatArray, r: FloatArray) = FloatArray(l.size) { (l[it] + r[it]) * 0.5f }

    /** [endsAt] ≥ 0: the stream ends inside this block, at that frame (the last block). */
    private class Job(val generation: Int, val start: Long, val samples: Array<FloatArray>, val endsAt: Long)

    /** Per-channel ring buffer addressed by absolute frame index. */
    private class Ring(channels: Int, val capacity: Int) {
        private val data = Array(channels) { FloatArray(capacity) }
        var start = 0L // oldest frame still kept

        fun ensureFrom(from: Long) {
            if (from > start) start = from
        }

        fun write(at: Long, src: FloatArray, frames: Int, channels: Int) {
            for (f in 0 until frames) {
                val idx = ((at + f) % capacity).toInt().let { if (it < 0) it + capacity else it }
                for (c in 0 until channels) data[c][idx] = src[f * channels + c]
            }
        }

        fun get(i: Long, c: Int): Float = data[c][idx(i)]

        fun getOrZero(i: Long, c: Int, end: Long): Float = if (i < 0 || i < start || i >= end) 0f else data[c][idx(i)]

        fun set(i: Long, c: Int, v: Float) {
            data[c][idx(i)] = v
        }

        private fun idx(i: Long) = (i % capacity).toInt().let { if (it < 0) it + capacity else it }
    }

    private companion object {
        const val SMALL_BUFFER = 4096 // frames buffered when karaoke mode isn't open
        const val SINC_MARGIN = 64 // extra input on each side of a block for the resampler
    }
}
