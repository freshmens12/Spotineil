package com.spotineil.core.player.local

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spotineil.core.model.TransitionMode
import kotlin.math.PI
import kotlin.math.abs
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
 * Before the swap, the silent tail player is lined up with the main one to within a few
 * milliseconds (see [lineUp]), so handing the old song over is seamless.
 * The next song's stream is looked up ahead of time, so it starts without waiting for YouTube.
 *
 * - [TransitionMode.Crossfade]: a [CROSSFADE_MS] fade.
 * - [TransitionMode.AutoMix]: a longer fade, [AUTOMIX_MS], like a DJ mix. It does not match beats.
 *
 * Both use an equal-power curve, so the volume stays steady through the blend.
 *
 * Skipping, seeking, pausing or changing the setting while a blend is running ends it cleanly.
 */
internal class TransitionController(
    private val player: ExoPlayer,
    private val prefetch: (MediaItem) -> Unit = {},
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
    private var handingOff = false
    private var handoffBeganAt = 0L
    private var handoffRampFrom = 0L
    private var blendElapsedMs = 0L
    private var lastBlendTickAt = 0L
    private var blendMs = 0L
    private var ignoreDiscontinuityUntil = 0L
    private var tailPlayingSince = 0L
    private var mainPlayingSince = 0L
    private var tailStartRequestedAt = 0L
    private var lineUpSeeks = 0
    private var linedUp = false
    private val offsets = ArrayDeque<Long>()

    /** How far ahead of the main player to start the tail: the time it takes to start playing. */
    private var startDelayMs = HANDOFF_LEAD_MS

    /** [startDelayMs] from the last blend that lined up: where the next one starts. */
    private var learnedStartDelayMs = HANDOFF_LEAD_MS

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, if (blending) BLEND_TICK_MS else TICK_MS)
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
        if (sameAlbumNext()) return // gapless: live albums and continuous mixes run straight on
        val remaining = duration - player.currentPosition
        // The tail starts a moment before the blend, silently, to line it up with the main player.
        when {
            remaining <= fade + ALIGN_LEAD_MS -> startBlend(key, fade)
            remaining <= fade + ALIGN_LEAD_MS + PREPARE_AHEAD_MS -> prepareTail(key, duration - fade - ALIGN_LEAD_MS)
        }
    }

    /** True when the next song is from the same album as this one (both named): play it gapless. */
    private fun sameAlbumNext(): Boolean {
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return false
        val a = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString()?.trim().orEmpty()
        val b = player.getMediaItemAt(next).mediaMetadata.albumTitle?.toString()?.trim().orEmpty()
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
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
            // Same audio session as the main player, so the equalizer and leveling apply to it too.
            runCatching { it.audioSessionId = player.audioSessionId }
            it.volume = 0f
            it.playbackParameters = player.playbackParameters
            it.setMediaItem(item, startAtMs.coerceAtLeast(0))
            it.playWhenReady = false
            it.prepare()
        }
        tailKey = key
        // Look the next song up now, so the main player can start it without waiting.
        player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { runCatching { prefetch(player.getMediaItemAt(it)) } }
    }

    private fun startBlend(key: String, fadeMs: Long) {
        armedKey = key
        if (tail == null || tailKey != key) prepareTail(key, player.currentPosition + HANDOFF_LEAD_MS)
        val t = tail ?: return
        t.addListener(tailListener)
        // The tail starts first, silently (it needs a moment to produce sound). Only once it plays,
        // lined up with the main player, do the two swap places, so there is no gap and no skip.
        t.volume = 0f
        lineUpSeeks = 0
        linedUp = false
        startDelayMs = learnedStartDelayMs
        restartTail(t)
        t.playWhenReady = player.playWhenReady

        blending = true
        handingOff = true
        handoffBeganAt = SystemClock.uptimeMillis()
        handoffRampFrom = 0L
        waitingForMain = false
        blendMs = fadeMs
    }

    /** Hands the old song from the main player to the tail player over a few hundred ms, then moves main on. */
    private fun advanceHandoff(t: ExoPlayer) {
        val now = SystemClock.uptimeMillis()
        if (!t.isPlaying) {
            tailPlayingSince = 0L
            offsets.clear()
            // Never got going (or the phone is paused): give up and let the song end normally.
            if (player.playWhenReady && now - tailStartRequestedAt > HANDOFF_TIMEOUT_MS) endBlend()
            return
        }
        if (tailPlayingSince == 0L) tailPlayingSince = now
        if (handoffRampFrom == 0L) {
            if (!linedUp) {
                when (lineUp(t, now)) {
                    LineUp.Waiting -> return
                    LineUp.GiveUp -> { endBlend(); return } // the song just plays to its end
                    LineUp.Done -> linedUp = true
                }
            }
            // Lined up early: keep both running (silently) until the blend is due.
            if (player.duration - player.currentPosition > blendMs + HANDOFF_MS) return
            handoffRampFrom = now
        }
        val p = ((now - handoffRampFrom).toFloat() / HANDOFF_MS).coerceIn(0f, 1f)
        player.volume = 1f - p
        t.volume = p
        if (p < 1f) return
        handingOff = false
        waitingForMain = true
        // The old song now lives on the tail player: hop to the next song, silent until it plays.
        ignoreDiscontinuityUntil = SystemClock.uptimeMillis() + IGNORE_MS
        player.volume = 0f
        player.seekToNextMediaItem()
    }

    /**
     * Lines the silent tail player up with the main one. Both play the same song, so an offset of even
     * a tenth of a second would sound like a skip or a stutter at the swap.
     *
     * The tail is seeked to where the main player will be once it has restarted. How long a restart
     * takes differs per phone, so each try measures it and the next seek allows for it (and the next
     * blend starts with what was learned).
     *
     * The measuring needs patience: for a second or so after a player starts, ExoPlayer only
     * estimates its position and then eases it to the real one (on a test phone the first estimate
     * said 7 ms off; it really was 450 ms). So the offset is read only once it holds steady.
     * Changing the playback speed instead of seeking would be smoother in theory, but ExoPlayer
     * drains its audio on every speed change, which stalls the player.
     */
    private fun lineUp(t: ExoPlayer, now: Long): LineUp {
        // Both positions must have settled: the main player's too, after a pause.
        val playingSince = maxOf(tailPlayingSince, mainPlayingSince)
        if (mainPlayingSince == 0L || now - playingSince < SETTLE_MIN_MS) {
            offsets.clear()
            return LineUp.Waiting
        }
        offsets.addLast(t.currentPosition - player.currentPosition) // > 0: the tail is ahead
        if (offsets.size > STEADY_SAMPLES) offsets.removeFirst()
        // Steady: the first and second half of the last half second agree (single readings jitter).
        val steady = offsets.size == STEADY_SAMPLES &&
            abs(offsets.take(STEADY_SAMPLES / 2).average() - offsets.drop(STEADY_SAMPLES / 2).average()) <= STEADY_DRIFT_MS
        if (!steady && now - playingSince < SETTLE_MAX_MS) return LineUp.Waiting
        val offset = offsets.average().toLong()
        if (abs(offset) <= ALIGNED_MS) {
            learnedStartDelayMs = startDelayMs
            return LineUp.Done
        }
        // It started [offset] ms off: a restart takes that much less (or more) than allowed for.
        startDelayMs = (startDelayMs - offset).coerceIn(0L, MAX_START_DELAY_MS)
        if (lineUpSeeks >= MAX_LINE_UP_SEEKS || now - handoffBeganAt > ALIGN_TIMEOUT_MS) {
            // Swapping now would skip or repeat a bit of the song: rather no blend this time.
            return LineUp.GiveUp
        }
        lineUpSeeks++
        restartTail(t)
        return LineUp.Waiting
    }

    /** Seeks the tail to where the main player will be once the tail has (re)started. */
    private fun restartTail(t: ExoPlayer) {
        t.seekTo(player.currentPosition + startDelayMs)
        tailPlayingSince = 0L
        tailStartRequestedAt = SystemClock.uptimeMillis()
        offsets.clear()
    }

    private enum class LineUp { Waiting, Done, GiveUp }

    private fun advanceBlend() {
        val t = tail
        if (t == null) { endBlend(); return }
        if (handingOff) { advanceHandoff(t); return }
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
        // Equal power: the two songs together stay as loud as one, with no dip in the middle.
        val up = sin(progress * PI.toFloat() / 2)
        val down = cos(progress * PI.toFloat() / 2)
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
        handingOff = false
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
        // Both restart after a pause, each with its own delay: measure again before the swap.
        if (playWhenReady && blending && handingOff && handoffRampFrom == 0L) {
            linedUp = false
            lineUpSeeks = 0
            handoffBeganAt = SystemClock.uptimeMillis()
            tailStartRequestedAt = handoffBeganAt
            offsets.clear()
        }
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

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        mainPlayingSince = if (isPlaying) SystemClock.uptimeMillis() else 0L
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE && blending) endBlend()
    }

    private companion object {
        const val TICK_MS = 150L
        const val BLEND_TICK_MS = 50L
        const val CROSSFADE_MS = 6_000L
        const val AUTOMIX_MS = 10_000L
        const val MIN_BLEND_MS = 1_500L
        const val PREPARE_AHEAD_MS = 12_000L
        const val HANDOFF_MS = 300L
        const val HANDOFF_LEAD_MS = 120L
        const val HANDOFF_TIMEOUT_MS = 4_000L
        const val ALIGN_LEAD_MS = 6_500L
        const val ALIGN_TIMEOUT_MS = 8_000L
        const val SETTLE_MIN_MS = 1_000L
        const val SETTLE_MAX_MS = 3_000L
        const val STEADY_SAMPLES = 10
        const val STEADY_DRIFT_MS = 6.0
        const val ALIGNED_MS = 25L
        const val MAX_LINE_UP_SEEKS = 3
        const val MAX_START_DELAY_MS = 1_500L
        const val IGNORE_MS = 1_000L
    }
}
