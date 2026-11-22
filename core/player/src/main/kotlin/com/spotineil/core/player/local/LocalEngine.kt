package com.spotineil.core.player.local

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotineil.core.model.NowPlayingTrack
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.PlaybackRestrictions
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.QueueItem
import com.spotineil.core.model.RepeatMode
import com.spotineil.core.network.auth.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Client side of [LocalPlaybackService], via a Media3 [MediaController]. */
@Singleton
class LocalEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private var controller: MediaController? = null

    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    /** Mirrors the controller's item count; MediaController itself may only be touched on the main thread. */
    @Volatile private var mediaItemCount = 0
    val hasMedia: Boolean get() = mediaItemCount > 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    private suspend fun controller(): MediaController = withContext(Dispatchers.Main) {
        controller?.let { return@withContext it }
        val token = SessionToken(context, ComponentName(context, LocalPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(true) }
        }
        c.addListener(listener)
        controller = c
        c
    }

    suspend fun play(items: List<PlayableMedia>, startIndex: Int, startPositionMs: Long = 0L) = withContext(Dispatchers.Main) {
        val c = controller()
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
    }

    suspend fun togglePlayPause() = withContext(Dispatchers.Main) {
        val c = controller()
        if (c.isPlaying) c.pause() else c.play()
    }

    suspend fun pause() = withContext(Dispatchers.Main) { controller?.pause(); Unit }
    suspend fun next() = withContext(Dispatchers.Main) { controller().seekToNext() }
    suspend fun previous() = withContext(Dispatchers.Main) { controller().seekToPrevious() }
    suspend fun seekTo(ms: Long) = withContext(Dispatchers.Main) { controller().seekTo(ms) }
    suspend fun toggleShuffle() = withContext(Dispatchers.Main) {
        val c = controller(); c.shuffleModeEnabled = !c.shuffleModeEnabled
    }
    suspend fun cycleRepeat() = withContext(Dispatchers.Main) {
        val c = controller()
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    suspend fun queue(): PlaybackQueue = withContext(Dispatchers.Main) {
        val c = controller()
        val current = c.currentMediaItemIndex
        val items = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueItem(
                key = "$i:${item.mediaId}",
                uri = item.mediaId,
                name = item.mediaMetadata.title?.toString().orEmpty(),
                artists = item.mediaMetadata.artist?.toString().orEmpty(),
                imageUrl = item.mediaMetadata.artworkUri?.toString(),
            )
        }
        PlaybackQueue(
            current = items.getOrNull(current),
            upcoming = if (current + 1 < items.size) items.subList(current + 1, items.size) else emptyList(),
            isReorderable = true,
        )
    }

    /** [from]/[to] are indices within the upcoming list. */
    suspend fun moveUpcoming(from: Int, to: Int) = withContext(Dispatchers.Main) {
        val c = controller()
        val base = c.currentMediaItemIndex + 1
        c.moveMediaItem(base + from, base + to)
    }

    suspend fun addToQueue(uri: String) = withContext(Dispatchers.Main) {
        val c = controller()
        val id = uri.substringAfter("ytmusic:video:", uri)
        val item = PlayableMedia(
            mediaId = "innertube:$id",
            uri = uri,
            title = id,
            artist = "YouTube Music",
        ).toMediaItem()
        c.addMediaItem(item)
    }

    suspend fun setUpNext(items: List<PlayableMedia>) = withContext(Dispatchers.Main) {
        val c = controller()
        val next = c.currentMediaItemIndex + 1
        if (next < c.mediaItemCount) c.removeMediaItems(next, c.mediaItemCount)
        c.addMediaItems(items.map { it.toMediaItem() })
    }

    private fun publish(player: Player) {
        mediaItemCount = player.mediaItemCount
        val item = player.currentMediaItem
        if (item == null) {
            _state.value = null
            return
        }
        val md = item.mediaMetadata
        val extras = md.extras
        val isLive = extras?.getBoolean(EXTRA_IS_LIVE) == true || player.isCurrentMediaItemLive
        val isEpisode = extras?.getBoolean(EXTRA_IS_EPISODE) == true
        val mediaDuration = player.duration.takeIf { it > 0 } ?: 0L
        val media = PlayableMedia(
            mediaId = item.mediaId,
            uri = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString().orEmpty(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString(),
            artworkUrl = md.artworkUri?.toString(),
            isLive = isLive,
            durationMs = extras?.getLong(EXTRA_DURATION_MS)?.takeIf { it > 0 } ?: mediaDuration,
            isPreview = extras?.getBoolean(EXTRA_IS_PREVIEW) == true,
            isEpisode = isEpisode,
        )
        _state.value = PlaybackState(
            source = PlaybackSource.Local,
            track = NowPlayingTrack(
                uri = item.mediaId,
                name = md.title?.toString().orEmpty(),
                artists = md.artist?.toString().orEmpty(),
                artistUri = null,
                album = md.albumTitle?.toString().orEmpty(),
                albumUri = null,
                durationMs = if (isLive) 0L else mediaDuration,
                imageUrl = md.artworkUri?.toString(),
                // Lyrics lookup skips "episodes"; live radio has no per-song metadata to match.
                isEpisode = isLive || isEpisode,
                media = media,
            ),
            restrictions = PlaybackRestrictions(
                canSkipNext = player.hasNextMediaItem() || !isLive,
                canSkipPrevious = !isLive || player.hasPreviousMediaItem(),
                canSeek = !isLive,
                canToggleShuffle = !isLive,
                canRepeat = !isLive,
            ),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            positionTimestamp = clock.now(),
            shuffle = player.shuffleModeEnabled,
            repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            deviceName = null,
        )
    }
}
