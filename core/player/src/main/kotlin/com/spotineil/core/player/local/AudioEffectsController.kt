package com.spotineil.core.player.local

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.spotineil.core.model.AudioSettings
import com.spotineil.core.model.EQ_BANDS_HZ
import kotlin.math.abs

/**
 * Settings → Sound, applied to the player's audio session:
 * - **Equalizer**: the 5 bands of [AudioSettings.gains], mapped onto the phone's own bands.
 * - **Volume leveling**: a gentle compressor and limiter (Android 9+), so quiet and loud songs
 *   come out at a similar level.
 * - **Skip silence** and **podcast speed** (episodes only; music always plays at 1x).
 *
 * Effects that the phone doesn't support are skipped quietly.
 */
@OptIn(UnstableApi::class)
internal class AudioEffectsController(private val player: ExoPlayer) : Player.Listener {

    private var settings = AudioSettings()
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var leveler: DynamicsProcessing? = null

    fun start() {
        player.addListener(this)
        attach(player.audioSessionId)
    }

    fun update(new: AudioSettings) {
        settings = new
        applyEqualizer()
        applyLeveling()
        player.skipSilenceEnabled = new.skipSilence
        applySpeed(player.currentMediaItem)
    }

    fun release() {
        player.removeListener(this)
        releaseEffects()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) = attach(audioSessionId)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applySpeed(mediaItem)

    private fun attach(id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET || id == sessionId) return
        releaseEffects()
        sessionId = id
        applyEqualizer()
        applyLeveling()
    }

    private fun applyEqualizer() {
        if (!settings.eqEnabled || sessionId == C.AUDIO_SESSION_ID_UNSET) {
            equalizer?.enabled = false
            return
        }
        val eq = equalizer ?: runCatching { Equalizer(0, sessionId) }.getOrNull()?.also { equalizer = it } ?: return
        runCatching {
            val (minLevel, maxLevel) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            val gains = settings.gains
            for (band in 0 until eq.numberOfBands) {
                // The phone's band → the nearest of our 5 bands (centre frequencies in mHz).
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                val nearest = EQ_BANDS_HZ.indices.minByOrNull { abs(EQ_BANDS_HZ[it] - hz) } ?: 0
                val level = (gains.getOrElse(nearest) { 0 } * 100).coerceIn(minLevel, maxLevel)
                eq.setBandLevel(band.toShort(), level.toShort())
            }
            eq.enabled = true
        }
    }

    private fun applyLeveling() {
        if (!settings.volumeLeveling || sessionId == C.AUDIO_SESSION_ID_UNSET) {
            leveler?.enabled = false
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val dp = leveler ?: runCatching { createLeveler(sessionId) }.getOrNull()?.also { leveler = it } ?: return
        runCatching { dp.enabled = true }
    }

    /** One-band compressor (evens out loudness) followed by a limiter (stops peaks clipping). */
    private fun createLeveler(session: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, // channels
            false, 0, // pre-EQ
            true, 1, // multi-band compressor: one band
            false, 0, // post-EQ
            true, // limiter
        ).build()
        val band = config.getMbcBandByChannelIndex(0, 0).apply {
            isEnabled = true
            cutoffFrequency = 20_000f
            attackTime = 10f
            releaseTime = 300f
            ratio = 3f
            threshold = -24f
            kneeWidth = 6f
            postGain = 6f
        }
        config.setMbcBandAllChannelsTo(0, band)
        config.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f),
        )
        return DynamicsProcessing(0, session, config)
    }

    private fun applySpeed(item: MediaItem?) {
        val isEpisode = item?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_EPISODE) == true
        val speed = if (isEpisode) settings.podcastSpeed.coerceIn(0.5f, 3f) else 1f
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { leveler?.release() }
        equalizer = null
        leveler = null
    }
}
