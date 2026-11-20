package com.spotineil.core.player

import android.content.Context
import com.spotineil.core.model.Device
import com.spotineil.core.model.LocalTrack
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.RemoteConnectionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What to play from the Spotify catalog.
 * - [contextUri] + [index]: start a playlist/album/artist/collection at a track.
 * - [contextUri] alone: play the whole context.
 * - [uri] alone: play a single track/episode.
 */
data class PlayRequest(
    val uri: String? = null,
    val contextUri: String? = null,
    val index: Int? = null,
    val shuffle: Boolean? = null,
)

sealed interface PlayerEvent {
    /** Spotify Free: the requested on-demand action isn't available. Never bypassed. */
    data object PremiumRequired : PlayerEvent
    data object NoActiveDevice : PlayerEvent
    data object SpotifyNotInstalled : PlayerEvent
    data object SpotifyNotLoggedIn : PlayerEvent
    data class Error(val message: String?) : PlayerEvent
}

sealed interface SleepTimerOption {
    data class Minutes(val minutes: Int) : SleepTimerOption
    data object EndOfTrack : SleepTimerOption
}

data class SleepTimerState(val endsAtMs: Long? = null, val endOfTrack: Boolean = false) {
    val isActive: Boolean get() = endsAtMs != null || endOfTrack
}

/**
 * Single entry point for playback. Routes commands to:
 *  1. the local Media3 player when local files are playing,
 *  2. the Spotify App Remote SDK when connected (primary for Spotify content),
 *  3. the Web API Spotify Connect endpoints otherwise (remote devices).
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    val connection: StateFlow<RemoteConnectionState>

    /** From App Remote capabilities: false for Spotify Free (shuffle-only on mobile). */
    val canPlayOnDemand: StateFlow<Boolean?>
    val events: SharedFlow<PlayerEvent>
    val sleepTimer: StateFlow<SleepTimerState>

    fun connectSpotify(context: Context)
    fun disconnectSpotify()

    fun play(request: PlayRequest)
    fun playLocal(tracks: List<LocalTrack>, startIndex: Int)

    /** Plays non-Spotify media (free tracks, previews, radio, local files) on the device. */
    fun playMedia(items: List<com.spotineil.core.model.PlayableMedia>, startIndex: Int)
    fun togglePlayPause()

    /** Pauses whatever is playing (e.g. before YouTube's own player starts a video). */
    fun pause() {
        if (state.value.isPlaying) togglePlayPause()
    }

    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun addToQueue(uri: String)

    /** Replaces everything after the current item (Start radio / autoplay) without interrupting it. */
    fun setUpNext(items: List<com.spotineil.core.model.PlayableMedia>)

    suspend fun queue(): PlaybackQueue
    /** Only effective for reorderable (local) queues; indices are within `upcoming`. */
    fun moveQueueItem(from: Int, to: Int)

    suspend fun devices(): List<Device>
    fun transferTo(device: Device)

    fun setSleepTimer(option: SleepTimerOption?)
}
