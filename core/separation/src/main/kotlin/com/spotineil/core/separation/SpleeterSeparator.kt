package com.spotineil.core.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Finds the voice in a block of stereo audio at [SAMPLE_RATE]. */
interface VoiceModel : Closeable {
    /**
     * Estimates the singing voice in [left]/[right] (each [BLOCK_SAMPLES] long, 44.1 kHz) and writes it
     * to [voiceLeft]/[voiceRight]. The first and last ~0.1 s are less reliable (callers overlap blocks).
     */
    fun voice(left: FloatArray, right: FloatArray, voiceLeft: FloatArray, voiceRight: FloatArray)

    companion object {
        const val SAMPLE_RATE = 44_100
        const val N_FFT = 4096
        const val HOP = 1024
        const val FRAMES = 512 // the model works on 512 STFT frames at a time
        const val BINS = 1024 // ...and the lowest 1024 bins (up to ~11 kHz)
        const val BLOCK_SAMPLES = (FRAMES - 1) * HOP + N_FFT // 527 360 samples ≈ 11.96 s
    }
}

/**
 * Deezer's Spleeter 2-stems model (MIT licensed), as converted to ONNX by the sherpa-onnx project:
 * one network estimates the voice's spectrogram, the other the accompaniment's. Their ratio
 * (a soft "Wiener" mask) says how much of each time-frequency point is voice.
 *
 * Above ~11 kHz the model doesn't look, and nothing there is taken out, so cymbals and air stay.
 */
class SpleeterVoiceModel(vocalsModel: File, accompanimentModel: File, threads: Int = 2) : VoiceModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val vocals = env.createSession(vocalsModel.path, options)
    private val accompaniment = env.createSession(accompanimentModel.path, options)

    private val fft = RealFft(VoiceModel.N_FFT)
    private val window = hann(VoiceModel.N_FFT)
    private val frameBins = VoiceModel.N_FFT / 2 + 1
    private val frames = VoiceModel.FRAMES
    private val bins = VoiceModel.BINS

    // Per channel: the full STFT (frames × frameBins) and the model's magnitude input.
    private val re = Array(2) { FloatArray(frames * frameBins) }
    private val im = Array(2) { FloatArray(frames * frameBins) }
    private val magnitude = FloatArray(2 * frames * bins)
    private val frame = FloatArray(VoiceModel.N_FFT)
    private val binRe = FloatArray(frameBins)
    private val binIm = FloatArray(frameBins)

    override fun voice(left: FloatArray, right: FloatArray, voiceLeft: FloatArray, voiceRight: FloatArray) {
        val input = arrayOf(left, right)
        for (c in 0..1) stft(input[c], c)

        val (v, a) = FloatBuffer.wrap(magnitude).let { buf ->
            OnnxTensor.createTensor(env, buf, longArrayOf(2, 1, frames.toLong(), bins.toLong())).use { x ->
                val vOut = vocals.run(mapOf(vocals.inputNames.first() to x)).use { (it[0] as OnnxTensor).floatBuffer.copy() }
                val aOut = accompaniment.run(mapOf(accompaniment.inputNames.first() to x)).use { (it[0] as OnnxTensor).floatBuffer.copy() }
                vOut to aOut
            }
        }

        val out = arrayOf(voiceLeft, voiceRight)
        for (c in 0..1) maskedIstft(c, v, a, out[c])
    }

    private fun stft(samples: FloatArray, c: Int) {
        for (f in 0 until frames) {
            val start = f * VoiceModel.HOP
            for (i in 0 until VoiceModel.N_FFT) frame[i] = samples[start + i] * window[i]
            fft.forward(frame, binRe, binIm)
            System.arraycopy(binRe, 0, re[c], f * frameBins, frameBins)
            System.arraycopy(binIm, 0, im[c], f * frameBins, frameBins)
            val m = (c * frames + f) * bins
            for (k in 0 until bins) {
                val r = binRe[k]
                val i = binIm[k]
                magnitude[m + k] = sqrt(r * r + i * i)
            }
        }
    }

    /** Applies the voice mask to channel [c]'s STFT and overlap-adds it back to sound. */
    private fun maskedIstft(c: Int, v: FloatArray, a: FloatArray, out: FloatArray) {
        out.fill(0f)
        val norm = FloatArray(out.size)
        for (f in 0 until frames) {
            val base = f * frameBins
            val m = (c * frames + f) * bins
            for (k in 0 until frameBins) {
                val mask = if (k < bins) {
                    val vs = v[m + k] * v[m + k]
                    val acc = a[m + k] * a[m + k]
                    (vs + 0.5e-10f) / (vs + acc + 1e-10f)
                } else {
                    0f
                }
                binRe[k] = re[c][base + k] * mask
                binIm[k] = im[c][base + k] * mask
            }
            fft.inverse(binRe, binIm, frame)
            val start = f * VoiceModel.HOP
            for (i in 0 until VoiceModel.N_FFT) {
                out[start + i] += frame[i] * window[i]
                norm[start + i] += window[i] * window[i]
            }
        }
        for (i in out.indices) out[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
    }

    override fun close() {
        vocals.close()
        accompaniment.close()
        options.close()
    }

    private fun FloatBuffer.copy(): FloatArray = FloatArray(remaining()).also { get(it) }
}
