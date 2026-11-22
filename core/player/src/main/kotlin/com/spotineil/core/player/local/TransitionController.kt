package com.spotineil.core.player.local

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spotineil.core.model.TransitionMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Blends one song into the next (Settings → Song transition).
 *
 * How it works: shortly before a song ends, a second, silent "tail" player is prepared on the same
 * song at the spot where the blend will start. At that moment the main player jumps to the next
 * song (starting silent) while the tail player carries on with the end of the old one. Both are
 * then faded over a few seconds, the old one down and the new one up, and the tail player is
 * released. Everything runs on the main thread.
 *
 * - [TransitionMode.Crossfade]: a straight fade, [CROSSFADE_MS] long.
 * - [TransitionMode.AutoMix]: a longer fade, [AUTOMIX_MS], on an equal-power curve so the volume
 *   stays steady through the blend, like a DJ mix. It does not match beats.
 *
 * Skipping, seeking, pausing or changing the setting while a blend is running ends it cleanly.
 */
internal class TransitionController(
    private val player: ExoPlayer,
    private val createTailPlayer: () -> ExoPlayer,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    var mode: TransitionMode = TransitionMode.Off
        set(value) {
            if (field == value) return
            field = value
            // A change (including to Off) ends a blend in progress and re-arms for the current song.
            endBlend()
            discardTail()
            armedKey = null
        }

    private var tail: ExoPlayer? = null
    private var tailKey: String? = null
    private var armedKey: String? = null

    private var blending = false
    private var waitingForMain = false
    private var blendElapsedMs = 0L
    private var lastBlendTickAt = 0L
    private var blendMs = 0L
    private var ignoreDiscontinuityUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        player.addListener(this)
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player.removeListener(this)
        endBlend()
        discardTail()
    }

    private fun step() {
        if (blending) {
            advanceBlend()
            return
        }
        if (mode == TransitionMode.Off) return
        if (!player.isPlaying || !player.hasNextMediaItem()) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE || player.isCurrentMediaItemLive || !player.isCurrentMediaItemSeekable) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val fade = blendLengthFor(duration)
        if (fade < MIN_BLEND_MS) return

        val key = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}"
        if (key == armedKey) return // already blended out of this song
        val remaining = duration - player.currentPosition
        when {
            remaining <= fade -> startBlend(key, duration, fade)
            remaining <= fade + PREPARE_AHEAD_MS -> prepareTail(key, duration - fade)
        }
    }

    private fun blendLengthFor(durationMs: Long): Long {
        val wanted = if (mode == TransitionMode.AutoMix) AUTOMIX_MS else CROSSFADE_MS
        // Never more than a quarter of a short song.
        return min(wanted, durationMs / 4)
    }

    /** Loads the end of the current song into the tail player, paused and silent, ready to take over. */
    private fun prepareTail(key: String, startAtMs: Long) {
        if (tail != null && tailKey == key) return
        discardTail()
        val item = player.currentMediaItem ?: return
        tail = createTailPlayer().also {
            it.volume = 0f
            it.setMediaItem(item, startAtMs.coerceAtLeast(0))
            it.playWhenReady = false
            it.prepare()
        }
        tailKey = key
    }

    private fun startBlend(key: String, durationMs: Long, fadeMs: Long) {
        armedKey = key
        if (tail == null || tailKey != key) prepareTail(key, player.currentPosition)
        val t = tail ?: return
        t.seekTo(player.currentPosition)
        t.volume = 1f
        t.playWhenReady = player.playWhenReady
        t.addListener(tailListener)

        blending = true
        waitingForMain = true
        blendMs = fadeMs
        // Hop to the next song, silent until it is actually playing.
        ignoreDiscontinuityUntil = SystemClock.uptimeMillis() + IGNORE_MS
        player.volume = 0f
        player.seekToNextMediaItem()
    }

    private fun advanceBlend() {
        val t = tail
        if (t == null) { endBlend(); return }
        if (waitingForMain) {
            // The next song is still loading: the old one keeps playing at full volume meanwhile.
            if (t.playbackState == Player.STATE_ENDED) { endBlend(); return }
            if (!player.isPlaying) return
            waitingForMain = false
            blendElapsedMs = 0L
            lastBlendTickAt = SystemClock.uptimeMillis()
            // Only as long as the old song has left.
            val left = (t.duration - t.currentPosition).takeIf { t.duration != C.TIME_UNSET } ?: blendMs
            blendMs = min(blendMs, left.coerceAtLeast(0))
            if (blendMs < MIN_BLEND_MS) { endBlend(); return }
        }
        if (!player.playWhenReady) return // paused: hold the blend where it is
        val now = SystemClock.uptimeMillis()
        blendElapsedMs += (now - lastBlendTickAt).coerceIn(0L, 500L)
        lastBlendTickAt = now
        val progress = (blendElapsedMs.toFloat() / blendMs).coerceIn(0f, 1f)
        val equalPower = mode == TransitionMode.AutoMix
        val up = if (equalPower) sin(progress * PI.toFloat() / 2) else progress
        val down = if (equalPower) cos(progress * PI.toFloat() / 2) else 1f - progress
        player.volume = up
        t.volume = down
        if (progress >= 1f) endBlend()
    }

    private val tailListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // The old song's tail failed to play: just let the new song come in.
            if (blending) endBlend() else discardTail()
        }
    }

    /** Stops the blend: the main player is back at full volume and the tail player is gone. */
    private fun endBlend() {
        val wasBlending = blending
        blending = false
        waitingForMain = false
        if (wasBlending || player.volume != 1f) player.volume = 1f
        discardTail()
    }

    private fun discardTail() {
        tail?.let {
            it.removeListener(tailListener)
            it.release()
        }
        tail = null
        tailKey = null
    }

    // The tail player follows the main one's play / pause.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        tail?.takeIf { blending }?.playWhenReady = playWhenReady
    }

    // The listener fires when the person skips, seeks or the queue changes by itself: end the blend.
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (SystemClock.uptimeMillis() < ignoreDiscontinuityUntil) return
        if (blending) endBlend()
        // A different song is now current: the prepared tail (if any) belongs to the old one.
        if (tail != null && !blending) discardTail()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE && blending) endBlend()
    }

    private companion object {
        const val TICK_MS = 150L
        const val CROSSFADE_MS = 6_000L
        const val AUTOMIX_MS = 10_000L
        const val MIN_BLEND_MS = 1_500L
        const val PREPARE_AHEAD_MS = 3_000L
        const val IGNORE_MS = 1_000L
    }
}
