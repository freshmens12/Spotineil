package com.spotineil.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotineil.app.widget.WidgetEntryPoint
import com.spotineil.core.model.PlayableMedia
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays a short tone (written to the app's cache) through the app's real player — the same
 * Media3 service, queue and audio effects music goes through — and checks that it starts playing.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {

    @Test
    fun aSongStartsPlaying() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "smoke-tone.wav").also { writeTone(it, seconds = 8) }
        val player = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).playbackController()

        val media = PlayableMedia(
            mediaId = "smoke:tone",
            uri = file.toURI().toString(),
            title = "Smoke test tone",
            artist = "Spotineil",
            durationMs = 8_000,
        )
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.playMedia(listOf(media), 0) }

        val playing = withTimeoutOrNull(30_000) {
            while (!(player.state.value.isPlaying && player.state.value.track?.uri == "smoke:tone")) delay(250)
            true
        } ?: false
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.pause() }
        assertTrue("The test tone never started playing (state: ${player.state.value})", playing)
    }

    /** A 440 Hz mono 16-bit WAV file. */
    private fun writeTone(file: File, seconds: Int) {
        val rate = 22_050
        val samples = rate * seconds
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); int(36 + samples * 2); out.writeBytes("WAVE")
            out.writeBytes("fmt "); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
            out.writeBytes("data"); int(samples * 2)
            val buf = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val v = (sin(2 * PI * 440 * i / rate) * 8_000).toInt()
                buf[2 * i] = v.toByte()
                buf[2 * i + 1] = (v shr 8).toByte()
            }
            out.write(buf)
        }
    }
}
