package com.spotineil.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.spotineil.core.data.repository.CatalogRepository
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.PlaylistRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.PlaylistTrack
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistUiState(
    val playlist: Playlist? = null,
    val isOwner: Boolean = false,
    val isFollowing: Boolean = false,
    val error: Throwable? = null,
    /** Non-null while the reorder/remove editor is open. */
    val editItems: List<PlaylistTrack>? = null,
    val editLoading: Boolean = false,
) {
    val canEdit: Boolean get() = isOwner || playlist?.collaborative == true
}

sealed interface PlaylistEvent {
    data object Saved : PlaylistEvent
    data class Failed(val error: Throwable) : PlaylistEvent
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val users: UserRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<PlaylistRoute>().id

    private val _state = MutableStateFlow(PlaylistUiState())
    val state: StateFlow<PlaylistUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaylistEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PlaylistEvent> = _events.asSharedFlow()

    private val reloadTracks = MutableStateFlow(0)
    val tracks: Flow<PagingData<PlaylistTrack>> = reloadTracks
        .flatMapLatest { catalog.playlistTracks(id) }
        .cachedIn(viewModelScope)

    val nowPlayingUri: StateFlow<String?> = player.state.map { it.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var snapshotId: String? = null
    private var dragFrom: Int = -1

    init { load() }

    fun load() = viewModelScope.launch {
        catalog.cachedPlaylist(id)?.let { p -> _state.update { it.copy(playlist = p) } }
        _state.update { it.copy(error = null) }
        val me = users.cachedCurrentUser() ?: runCatching { users.refreshCurrentUser() }.getOrNull()
        runCatching { catalog.playlist(id, me?.id) }
            .onSuccess { d ->
                snapshotId = d.playlist.snapshotId
                _state.update { it.copy(playlist = d.playlist, isOwner = d.isOwnedByMe, isFollowing = d.isFollowing) }
            }
            .onFailure { e -> _state.update { it.copy(error = if (it.playlist == null) e else null) } }
    }

    fun play(index: Int? = null, shuffle: Boolean? = null) {
        val uri = _state.value.playlist?.uri ?: return
        player.play(PlayRequest(contextUri = uri, index = index, shuffle = shuffle ?: if (index == null) false else null))
    }

    fun toggleFollow() = viewModelScope.launch {
        val target = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = target) }
        runCatching { library.setFollowingPlaylist(id, target) }
            .onFailure { e -> _state.update { it.copy(isFollowing = !target) }; _events.tryEmit(PlaylistEvent.Failed(e)) }
    }

    fun updateDetails(name: String, description: String, public: Boolean, collaborative: Boolean) = viewModelScope.launch {
        runCatching { playlists.updateDetails(id, name.trim(), description.trim(), public, collaborative) }
            .onSuccess {
                _state.update { s ->
                    s.copy(
                        playlist = s.playlist?.copy(
                            name = name.trim(),
                            description = description.trim().ifBlank { null },
                            isPublic = public && !collaborative,
                            collaborative = collaborative,
                        ),
                    )
                }
                _events.tryEmit(PlaylistEvent.Saved)
            }
            .onFailure { _events.tryEmit(PlaylistEvent.Failed(it)) }
    }

    // ---- Editor (reorder / remove) ----

    fun openEditor() = viewModelScope.launch {
        _state.update { it.copy(editLoading = true) }
        runCatching { catalog.allPlaylistTracks(id) }
            .onSuccess { items -> _state.update { it.copy(editItems = items, editLoading = false) } }
            .onFailure { e -> _state.update { it.copy(editLoading = false) }; _events.tryEmit(PlaylistEvent.Failed(e)) }
    }

    fun closeEditor() {
        _state.update { it.copy(editItems = null) }
        reloadTracks.value++
        load()
    }

    /** Live reorder while dragging (local only). */
    fun moveLocal(from: Int, to: Int) {
        _state.update { s ->
            val list = s.editItems?.toMutableList() ?: return@update s
            if (from !in list.indices || to !in list.indices) return@update s
            list.add(to, list.removeAt(from))
            s.copy(editItems = list)
        }
    }

    fun onDragStart(key: String) {
        dragFrom = _state.value.editItems?.indexOfFirst { it.key() == key } ?: -1
    }

    /** Commit the move to Spotify when the drag ends. */
    fun onDragEnd(key: String) {
        val from = dragFrom
        val to = _state.value.editItems?.indexOfFirst { it.key() == key } ?: -1
        dragFrom = -1
        if (from < 0 || to < 0 || from == to) return
        viewModelScope.launch {
            runCatching { playlists.move(id, from, to, snapshotId) }
                .onSuccess { snapshotId = it; renumber() }
                .onFailure { e ->
                    moveLocal(to, from) // roll back
                    _events.tryEmit(PlaylistEvent.Failed(e))
                }
        }
    }

    fun remove(item: PlaylistTrack) = viewModelScope.launch {
        val before = _state.value.editItems
        _state.update { s -> s.copy(editItems = s.editItems?.filterNot { it.key() == item.key() }) }
        runCatching { playlists.removeTrack(id, item.track.uri, item.position, snapshotId) }
            .onSuccess { snapshotId = it; renumber() }
            .onFailure { e -> _state.update { it.copy(editItems = before) }; _events.tryEmit(PlaylistEvent.Failed(e)) }
    }

    /** Removal from the normal (paged) view's context menu. */
    fun removeFromPaged(item: PlaylistTrack) = viewModelScope.launch {
        runCatching { playlists.removeTrack(id, item.track.uri, item.position, snapshotId) }
            .onSuccess { snapshotId = it; reloadTracks.value++ }
            .onFailure { _events.tryEmit(PlaylistEvent.Failed(it)) }
    }

    /** Positions are server indices; keep them in sync after each edit. */
    private fun renumber() {
        _state.update { s -> s.copy(editItems = s.editItems?.mapIndexed { i, t -> t.copy(position = i) }) }
    }
}

/** Stable key for a playlist row (the same track may appear more than once). */
fun PlaylistTrack.key(): String = "${track.uri}#${addedAt}#${track.id}#${System.identityHashCode(track)}"
