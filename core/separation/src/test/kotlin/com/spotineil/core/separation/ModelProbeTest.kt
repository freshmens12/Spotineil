package com.spotineil.core.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints the model's inputs/outputs and timings. Runs only when SPLEETER_DIR points at the model files. */
class ModelProbeTest {
    @Test
    fun probe() {
        val dir = System.getenv("SPLEETER_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        for (name in dir!!.listFiles { f -> f.name.endsWith(".onnx") }!!) {
            val s = env.createSession(name.path, opts)
            println("== ${name.name}")
            s.inputInfo.forEach { (k, v) -> println("  in  $k: ${v.info}") }
            s.outputInfo.forEach { (k, v) -> println("  out $k: ${v.info}") }
            println("  meta: ${s.metadata.customMetadata}")
            for (frames in listOf(512, 128)) {
                val data = FloatBuffer.allocate(2 * frames * 1024)
                repeat(data.capacity()) { data.put(it, (it % 97) / 97f) }
                val t = OnnxTensor.createTensor(env, data, longArrayOf(2, 1, frames.toLong(), 1024))
                runCatching {
                    val t0 = System.nanoTime()
                    s.run(mapOf(s.inputNames.first() to t)).use { r ->
                        val out = r[0] as OnnxTensor
                        println("  frames=$frames -> ${out.info.shape.contentToString()} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                }.onFailure { println("  frames=$frames failed: ${it.message?.take(200)}") }
            }
            s.close()
        }
    }
}
