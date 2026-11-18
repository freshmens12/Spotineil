package com.spotineil.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.LyricsQuery
import com.spotineil.core.data.repository.LyricsRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.Device
import com.spotineil.core.model.Lyrics
import com.spotineil.core.model.NowPlayingTrack
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.Product
import com.spotineil.core.model.RemoteConnectionState
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.player.SleepTimerOption
import com.spotineil.core.player.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playback: PlaybackState = PlaybackState(),
    val positionMs: Long = 0,
    val isLiked: Boolean? = null,
    val showFreeNotice: Boolean = false,
    val connection: RemoteConnectionState = RemoteConnectionState.Disconnected,
    val sleepTimer: SleepTimerState = SleepTimerState(),
) {
    val track: NowPlayingTrack? get() = playback.track
    val hasTrack: Boolean get() = playback.track != null
    val progress: Float
        get() = track?.durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    /** Spotify tracks are liked on Spotify; free/local songs go to the on-device "Liked songs" (not radio). */
    val canLike: Boolean
        get() = (playback.source != PlaybackSource.Local && track?.spotifyId != null) ||
            (playback.source == PlaybackSource.Local && track?.media != null && track?.isLive == false)
}

/** Ticks the interpolated position so the seek bar moves smoothly between player updates. */
internal fun positionFlow(state: PlaybackState, tickMs: Long = 250, now: () -> Long = System::currentTimeMillis): Flow<Long> =
    if (!state.isPlaying) flowOf(state.positionMs)
    else flow {
        while (true) {
            emit(state.estimatedPosition(now()))
            delay(tickMs)
        }
    }

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlaybackController,
    private val library: LibraryRepository,
    private val likedMedia: LikedMediaRepository,
    users: UserRepository,
) : ViewModel() {

    private val liked = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<PlayerUiState> = combine(
        player.state,
        player.state.flatMapLatest { positionFlow(it) },
        liked,
        combine(player.canPlayOnDemand, users.currentUser) { onDemand, user -> onDemand == false || user?.product == Product.Free },
        combine(player.connection, player.sleepTimer) { c, s -> c to s },
    ) { playback, position, isLiked, freeNotice, (connection, sleep) ->
        PlayerUiState(playback, position, isLiked, freeNotice, connection, sleep)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    private val _devices = MutableStateFlow<List<Device>?>(null)
    val devices: StateFlow<List<Device>?> = _devices.asStateFlow()

    private val _queue = MutableStateFlow<PlaybackQueue?>(null)
    val queue: StateFlow<PlaybackQueue?> = _queue.asStateFlow()

    private val _queueError = MutableStateFlow<Throwable?>(null)
    val queueError: StateFlow<Throwable?> = _queueError.asStateFlow()

    init {
        viewModelScope.launch {
            player.state
                .map { s -> LikeKey(s.track?.spotifyId?.takeIf { s.source != PlaybackSource.Local }, s.track?.media?.takeIf { s.source == PlaybackSource.Local && !it.isLive }?.mediaId) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    liked.value = null
                    when {
                        key.spotifyId != null ->
                            liked.value = runCatching { library.areTracksSaved(listOf(key.spotifyId)).firstOrNull() }.getOrNull()
                        key.mediaId != null -> likedMedia.isLiked(key.mediaId).collect { liked.value = it }
                    }
                }
        }
    }

    private data class LikeKey(val spotifyId: String?, val mediaId: String?)

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun setSleepTimer(option: SleepTimerOption?) = player.setSleepTimer(option)

    fun toggleLike() {
        val state = uiState.value
        val media = state.track?.media
        if (state.playback.source == PlaybackSource.Local && media != null) {
            if (media.isLive) return
            val target = !(liked.value ?: false)
            liked.value = target
            viewModelScope.launch { likedMedia.setLiked(media, target) }
            return
        }
        val id = state.track?.spotifyId ?: return
        val target = !(liked.value ?: false)
        liked.value = target
        viewModelScope.launch {
            runCatching { library.setTrackSaved(id, target) }.onFailure { liked.value = !target }
        }
    }

    fun loadDevices() = viewModelScope.launch {
        _devices.value = null
        _devices.value = runCatching { player.devices() }.getOrDefault(emptyList())
    }

    fun transferTo(device: Device) = player.transferTo(device)

    fun loadQueue() = viewModelScope.launch {
        _queueError.value = null
        runCatching { player.queue() }
            .onSuccess { _queue.value = it }
            .onFailure { _queueError.value = it }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val q = _queue.value ?: return
        if (!q.isReorderable) return
        val list = q.upcoming.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _queue.value = q.copy(upcoming = list)
        player.moveQueueItem(from, to)
    }
}

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data object NotAvailable : LyricsUiState
    data class Loaded(val lyrics: Lyrics) : LyricsUiState
}

@HiltViewModel
class LyricsViewModel @Inject constructor(
    player: PlaybackController,
    private val repository: LyricsRepository,
) : ViewModel() {

    val state: StateFlow<LyricsUiState> = player.state
        .map { it.track }
        .distinctUntilChanged { a, b -> a?.uri == b?.uri && a?.durationMs == b?.durationMs }
        .flatMapLatest { track ->
            flow {
                if (track == null || track.isEpisode || track.name.isBlank()) {
                    emit(LyricsUiState.Idle)
                    return@flow
                }
                emit(LyricsUiState.Loading)
                val query = LyricsQuery(
                    trackName = track.name,
                    artistName = track.artists.substringBefore(',').trim(),
                    albumName = track.album.takeIf { it.isNotBlank() },
                    // For a 30 s preview, match against the full song's length.
                    durationMs = track.lyricsDurationMs,
                )
                val lyrics = runCatching { repository.lyrics(query) }.getOrNull()
                    // A preview starts somewhere in the middle of the song, so line timings wouldn't
                    // line up: show the words without syncing.
                    ?.let { if (track.isPreview && it.isSynced) it.copy(isSynced = false) else it }
                emit(if (lyrics == null) LyricsUiState.NotAvailable else LyricsUiState.Loaded(lyrics))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState.Idle)
}
