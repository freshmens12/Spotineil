package com.spotineil.core.player

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How loud the music is right now (0..1), for visuals such as the living cover on the Now playing
 * screen. The player measures each piece of sound as it goes to the speaker and notes when it will
 * be heard; [at] looks that up. Nothing is recorded: it is only a loudness number.
 */
object AudioLevel {
    private const val SIZE = 512
    private val times = LongArray(SIZE)
    private val levels = FloatArray(SIZE)
    private var next = 0
    private var count = 0

    /** Roughly how long sound takes from the player to the ear (speaker buffer and device). */
    private const val OUTPUT_LATENCY_NS = 300_000_000L

    /** Records [samples] interleaved floats (-1..1) just handed to the speaker. */
    fun record(samples: FloatArray, count: Int, nowNanos: Long = System.nanoTime()) {
        if (count <= 0) return
        var sum = 0.0
        var i = 0
        while (i < count) {
            val s = samples[i]
            sum += s * s
            i++
        }
        val rms = sqrt(sum / count)
        // -45 dB (quiet) .. -6 dB (loud) → 0..1
        val db = if (rms <= 1e-6) -120.0 else 20 * log10(rms)
        val level = ((db + 45) / 39).toFloat().coerceIn(0f, 1f)
        synchronized(this) {
            times[next] = nowNanos + OUTPUT_LATENCY_NS
            levels[next] = level
            next = (next + 1) % SIZE
            if (this.count < SIZE) this.count++
        }
    }

    /** The loudness heard at [nanos] (System.nanoTime), or 0 when nothing is playing. */
    fun at(nanos: Long = System.nanoTime()): Float = synchronized(this) {
        var best = -1
        var bestTime = Long.MIN_VALUE
        for (k in 0 until count) {
            val t = times[k]
            if (t <= nanos && t > bestTime) {
                bestTime = t
                best = k
            }
        }
        if (best < 0 || nanos - bestTime > 250_000_000L) 0f else levels[best]
    }
}
