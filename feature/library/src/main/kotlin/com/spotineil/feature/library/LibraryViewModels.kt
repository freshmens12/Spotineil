package com.spotineil.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.LocalMediaRepository
import com.spotineil.core.data.repository.PlaylistRepository
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.LibraryItem
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.LibrarySort
import com.spotineil.core.model.LocalTrack
import com.spotineil.core.model.Track
import com.spotineil.core.model.UserSettings
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val playlists: PlaylistRepository,
    private val player: PlaybackController,
) : ViewModel() {

    private val _tab = MutableStateFlow(LibraryItemType.Playlist)
    val tab: StateFlow<LibraryItemType> = _tab.asStateFlow()

    val settingsState: StateFlow<UserSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val _likedCount = MutableStateFlow<Int?>(null)
    val likedCount: StateFlow<Int?> = _likedCount.asStateFlow()

    /** Emits the new playlist id after creation, for navigation. */
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    private val _error = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val error: SharedFlow<Throwable> = _error.asSharedFlow()

    val items: Flow<PagingData<LibraryItem>> = combine(
        _tab,
        settings.settings.map { it.librarySort }.distinctUntilChanged(),
    ) { t, s -> t to s }
        .flatMapLatest { (t, s) -> library.items(t, s) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch { _likedCount.value = runCatching { library.likedSongsCount() }.getOrNull() }
    }

    fun selectTab(type: LibraryItemType) { _tab.value = type }
    fun setSort(sort: LibrarySort) = viewModelScope.launch { settings.setLibrarySort(sort) }
    fun setGrid(grid: Boolean) = viewModelScope.launch { settings.setLibraryGrid(grid) }

    fun createPlaylist(name: String, description: String?, public: Boolean, collaborative: Boolean) {
        viewModelScope.launch {
            runCatching { playlists.create(name.trim(), description?.trim()?.ifBlank { null }, public, collaborative) }
                .onSuccess { _created.tryEmit(it.id) }
                .onFailure { _error.tryEmit(it) }
        }
    }

    fun playShow(uri: String) = player.play(PlayRequest(contextUri = uri))
}

@HiltViewModel
class LikedSongsViewModel @Inject constructor(
    library: LibraryRepository,
    private val users: UserRepository,
    private val player: PlaybackController,
) : ViewModel() {
    val tracks: Flow<PagingData<Track>> = library.likedSongs().cachedIn(viewModelScope)
    val nowPlayingUri: StateFlow<String?> = player.state.map { it.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun collectionUri(): String? =
        (users.cachedCurrentUser() ?: runCatching { users.refreshCurrentUser() }.getOrNull())
            ?.let { "spotify:user:${it.id}:collection" }

    fun play(index: Int, track: Track) = viewModelScope.launch {
        val ctx = collectionUri()
        player.play(if (ctx != null) PlayRequest(contextUri = ctx, index = index) else PlayRequest(uri = track.uri))
    }

    fun playAll(shuffle: Boolean) = viewModelScope.launch {
        collectionUri()?.let { player.play(PlayRequest(contextUri = it, shuffle = shuffle)) }
    }
}

sealed interface LocalFilesUiState {
    data object NeedsPermission : LocalFilesUiState
    data object Loading : LocalFilesUiState
    data class Loaded(val tracks: List<LocalTrack>) : LocalFilesUiState
}

@HiltViewModel
class LocalFilesViewModel @Inject constructor(
    private val localMedia: LocalMediaRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val _state = MutableStateFlow<LocalFilesUiState>(LocalFilesUiState.NeedsPermission)
    val state: StateFlow<LocalFilesUiState> = _state.asStateFlow()

    /** True on the Downloads page: only songs Spotineil saved. */
    var downloadsOnly = false

    private suspend fun load() = if (downloadsOnly) localMedia.downloads() else localMedia.tracks()

    fun onPermissionResult(granted: Boolean) {
        if (!granted) { _state.value = LocalFilesUiState.NeedsPermission; return }
        _state.value = LocalFilesUiState.Loading
        viewModelScope.launch { _state.value = LocalFilesUiState.Loaded(load()) }
    }

    /** Reads the files again (after one was deleted). */
    fun reload() {
        viewModelScope.launch { _state.value = LocalFilesUiState.Loaded(load()) }
    }

    fun play(tracks: List<LocalTrack>, index: Int) {
        if (tracks.isNotEmpty()) player.playLocal(tracks, index)
    }
}
