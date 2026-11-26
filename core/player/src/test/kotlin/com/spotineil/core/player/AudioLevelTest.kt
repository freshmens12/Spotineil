package com.spotineil.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    @Test
    fun loudnessIsReportedWhenItIsHeardAndFadesWhenTheMusicStops() {
        val t0 = 1_000_000_000_000L
        val loud = FloatArray(2048) { if (it % 2 == 0) 0.5f else -0.5f }
        val quiet = FloatArray(2048)
        AudioLevel.record(quiet, quiet.size, t0)
        AudioLevel.record(loud, loud.size, t0 + 100_000_000L)

        // Sound reaches the ear ~0.3 s after it is handed on.
        assertEquals(0f, AudioLevel.at(t0 + 350_000_000L), 0.001f) // the silence
        assertTrue(AudioLevel.at(t0 + 420_000_000L) > 0.8f) // the loud part
        // Nothing new for a while (paused): back to 0.
        assertEquals(0f, AudioLevel.at(t0 + 2_000_000_000L), 0.001f)
    }
}
