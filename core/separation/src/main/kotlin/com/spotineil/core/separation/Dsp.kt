package com.spotineil.core.separation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real FFT of a power-of-two size [n], computed with a complex FFT of half the size.
 * [forward] turns n samples into n/2+1 bins (re, im); [inverse] turns them back (scaled by 1/n).
 */
internal class RealFft(private val n: Int) {
    private val half = n / 2
    private val bits = Integer.numberOfTrailingZeros(half)
    private val rev = IntArray(half) { Integer.reverse(it) ushr (32 - bits) }
    private val cosT = DoubleArray(half / 2) { cos(2 * PI * it / half) }
    private val sinT = DoubleArray(half / 2) { -sin(2 * PI * it / half) }
    private val twCos = DoubleArray(half) { cos(PI * it / half) }
    private val twSin = DoubleArray(half) { -sin(PI * it / half) }
    private val zr = DoubleArray(half)
    private val zi = DoubleArray(half)

    init {
        require(n >= 4 && n and (n - 1) == 0) { "n must be a power of two" }
    }

    /** [input] has n samples; [re]/[im] receive n/2+1 bins. */
    fun forward(input: FloatArray, re: FloatArray, im: FloatArray) {
        for (k in 0 until half) {
            val r = rev[k]
            zr[r] = input[2 * k].toDouble()
            zi[r] = input[2 * k + 1].toDouble()
        }
        complexFft(inverse = false)
        // Split the half-size spectrum into the real signal's spectrum.
        re[0] = (zr[0] + zi[0]).toFloat()
        im[0] = 0f
        re[half] = (zr[0] - zi[0]).toFloat()
        im[half] = 0f
        for (k in 1 until half) {
            val ar = zr[k]
            val ai = zi[k]
            val br = zr[half - k]
            val bi = -zi[half - k]
            val er = (ar + br) * 0.5
            val ei = (ai + bi) * 0.5
            val or = (ai - bi) * 0.5
            val oi = -(ar - br) * 0.5
            val wr = twCos[k]
            val wi = twSin[k]
            re[k] = (er + wr * or - wi * oi).toFloat()
            im[k] = (ei + wr * oi + wi * or).toFloat()
        }
    }

    /** [re]/[im] hold n/2+1 bins; [output] receives n samples (the 1/n scale included). */
    fun inverse(re: FloatArray, im: FloatArray, output: FloatArray) {
        for (k in 0 until half) {
            val xr = re[k].toDouble()
            val xi = im[k].toDouble()
            val yr = re[half - k].toDouble()
            val yi = -im[half - k].toDouble()
            val er = xr + yr
            val ei = xi + yi
            val dr = xr - yr
            val di = xi - yi
            // odd part times conj(twiddle)
            val wr = twCos[k]
            val wi = -twSin[k]
            val or = dr * wr - di * wi
            val oi = dr * wi + di * wr
            val r = rev[k]
            zr[r] = er - oi
            zi[r] = ei + or
        }
        complexFft(inverse = true)
        val scale = 1.0 / n
        for (k in 0 until half) {
            output[2 * k] = (zr[k] * scale).toFloat()
            output[2 * k + 1] = (zi[k] * scale).toFloat()
        }
    }

    private fun complexFft(inverse: Boolean) {
        var size = 2
        while (size <= half) {
            val step = half / size
            val h = size / 2
            var start = 0
            while (start < half) {
                for (j in 0 until h) {
                    val c = cosT[j * step]
                    val s = if (inverse) -sinT[j * step] else sinT[j * step]
                    val a = start + j
                    val b = a + h
                    val tr = zr[b] * c - zi[b] * s
                    val ti = zr[b] * s + zi[b] * c
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}

/** Periodic Hann window (as used to train Spleeter). */
internal fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }

/**
 * Band-limited interpolation (windowed sinc, Kaiser window) used to move audio between the song's
 * sample rate and the model's 44.1 kHz. [cutoff] is relative to the lower of the two Nyquist rates.
 */
internal class SincInterpolator(private val zeroCrossings: Int = 16, cutoff: Double = 0.94) {
    private val phases = 256
    private val table: FloatArray
    private val tableCutoff = cutoff

    init {
        val len = zeroCrossings * phases
        table = FloatArray(len + 1)
        val beta = 8.0
        val i0b = besselI0(beta)
        for (i in 0..len) {
            val x = i.toDouble() / phases // in input samples (at scale 1)
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = x / zeroCrossings
            val w = if (r >= 1.0) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0b
            table[i] = (sinc * w).toFloat()
        }
    }

    /**
     * Resamples [src] (one channel, sample rate [srcRate]) to [count] samples at [dstRate], where
     * output sample j is at time [startSec] + j / dstRate, and [src][0] is at [srcStartSec].
     * Samples outside [src] count as silence.
     */
    fun resample(
        src: FloatArray,
        srcRate: Int,
        srcStartSec: Double,
        dst: FloatArray,
        dstRate: Int,
        startSec: Double,
        count: Int = dst.size,
    ) {
        val ratio = srcRate.toDouble() / dstRate
        // Scale the kernel down when shrinking the rate, so it also low-passes.
        val scale = minOf(1.0, dstRate.toDouble() / srcRate) * tableCutoff
        val reach = (zeroCrossings / scale).toInt() + 1
        for (j in 0 until count) {
            val pos = (startSec - srcStartSec) * srcRate + j * ratio
            val center = floor(pos).toInt()
            var acc = 0.0
            for (k in center - reach + 1..center + reach) {
                if (k < 0 || k >= src.size) continue
                val d = abs(pos - k) * scale
                val t = d * phases
                val ti = t.toInt()
                if (ti >= table.size - 1) continue
                val frac = t - ti
                val h = table[ti] + (table[ti + 1] - table[ti]) * frac
                acc += src[k] * h
            }
            dst[j] = (acc * scale).toFloat()
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            val t = x / (2 * k)
            term *= t * t
            sum += term
            k++
        }
        return sum
    }
}
