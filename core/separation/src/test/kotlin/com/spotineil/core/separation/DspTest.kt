package com.spotineil.core.separation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTest {

    @Test
    fun realFftMatchesAPlainDft() {
        val n = 64
        val x = FloatArray(n) { (sin(it * 0.37) + 0.5 * cos(it * 1.9) + (it % 5) * 0.1).toFloat() }
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        RealFft(n).forward(x, re, im)
        for (k in 0..n / 2) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                r += x[t] * cos(2 * PI * k * t / n)
                i -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals("re[$k]", r, re[k].toDouble(), 1e-3)
            assertEquals("im[$k]", i, im[k].toDouble(), 1e-3)
        }
    }

    @Test
    fun inverseFftRestoresTheSignal() {
        val n = 4096
        val x = FloatArray(n) { (sin(it * 0.011) * 0.7 + cos(it * 0.3) * 0.2).toFloat() }
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        val fft = RealFft(n)
        fft.forward(x, re, im)
        val y = FloatArray(n)
        fft.inverse(re, im, y)
        for (t in 0 until n) assertEquals(x[t], y[t], 1e-4f)
    }

    @Test
    fun resamplingKeepsATonesLevelAndTiming() {
        val tone = 1_000.0
        val src = FloatArray(48_000) { sin(2 * PI * tone * it / 48_000).toFloat() }
        val dst = FloatArray(44_100)
        SincInterpolator().resample(src, 48_000, 0.0, dst, 44_100, 0.0)
        // Compare with the ideal tone in the middle (edges see silence outside the input).
        var err = 0.0
        var sig = 0.0
        for (j in 2_000 until 42_000) {
            val ideal = sin(2 * PI * tone * j / 44_100)
            err += (dst[j] - ideal) * (dst[j] - ideal)
            sig += ideal * ideal
        }
        assertTrue("error ${sqrt(err / sig)}", sqrt(err / sig) < 0.01)
    }

    @Test
    fun hannWindowsOverlapAddToAConstant() {
        val w = hann(4096)
        val sum = DoubleArray(1024)
        for (shift in 0 until 4) for (i in 0 until 1024) sum[i] += (w[shift * 1024 + i] * w[shift * 1024 + i]).toDouble()
        val mean = sum.average()
        assertTrue(sum.all { abs(it - mean) < 1e-3 })
    }
}
