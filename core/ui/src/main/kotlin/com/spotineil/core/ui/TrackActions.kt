package com.spotineil.core.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.PlaylistRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.bestFor
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the context menu needs to know about a track. */
data class TrackActionTarget(
    val id: String,
    val uri: String,
    val name: String,
    val artists: String,
    val imageUrl: String?,
    val artistId: String?,
    val albumId: String?,
    /** Set when shown inside an editable playlist, enabling "Remove from playlist". */
    val removable: Boolean = false,
)

fun com.spotineil.core.model.Track.toActionTarget(removable: Boolean = false) = TrackActionTarget(
    id = id,
    uri = uri,
    name = name,
    artists = artistNames,
    imageUrl = images.bestFor(160),
    artistId = artists.firstOrNull()?.id,
    albumId = album?.id,
    removable = removable,
)

data class TrackActionsUiState(
    val isLiked: Boolean? = null,
    val playlists: List<Playlist>? = null,
    val pickingPlaylist: Boolean = false,
)

@HiltViewModel
class TrackActionsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val player: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(TrackActionsUiState())
    val state: StateFlow<TrackActionsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun load(target: TrackActionTarget) {
        _state.value = TrackActionsUiState()
        viewModelScope.launch {
            val liked = runCatching { library.areTracksSaved(listOf(target.id)).firstOrNull() }.getOrNull()
            _state.update { it.copy(isLiked = liked) }
        }
    }

    fun toggleLike(target: TrackActionTarget) {
        val newValue = !(_state.value.isLiked ?: false)
        viewModelScope.launch {
            runCatching { library.setTrackSaved(target.id, newValue) }
                .onSuccess {
                    _state.update { it.copy(isLiked = newValue) }
                    _messages.tryEmit(if (newValue) R.string.ui_added_liked else R.string.ui_removed_liked)
                }
                .onFailure { _messages.tryEmit(R.string.ui_action_failed) }
        }
    }

    fun addToQueue(target: TrackActionTarget) {
        player.addToQueue(target.uri)
        _messages.tryEmit(R.string.ui_added_queue)
    }

    fun showPlaylistPicker() {
        _state.update { it.copy(pickingPlaylist = true) }
        viewModelScope.launch {
            val list = runCatching { playlists.editablePlaylists() }.getOrDefault(emptyList())
            _state.update { it.copy(playlists = list) }
        }
    }

    fun addToPlaylist(target: TrackActionTarget, playlist: Playlist) {
        viewModelScope.launch {
            runCatching { playlists.addTracks(playlist.id, listOf(target.uri)) }
                .onSuccess { _messages.tryEmit(R.string.ui_added_playlist) }
                .onFailure { _messages.tryEmit(R.string.ui_action_failed) }
        }
    }
}

/**
 * Bottom-sheet context menu for a track. Messages (e.g. "Added to queue") are delivered
 * through [onMessage] so the host can show a snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    target: TrackActionTarget,
    onDismiss: () -> Unit,
    onGoToArtist: (String) -> Unit,
    onGoToAlbum: (String) -> Unit,
    onMessage: (String) -> Unit = {},
    onRemoveFromPlaylist: (() -> Unit)? = null,
    viewModel: TrackActionsViewModel = hiltViewModel(key = "track-actions"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(target.id) { viewModel.load(target) }
    LaunchedEffect(Unit) { viewModel.messages.collect { onMessage(context.getString(it)) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(target.imageUrl, null, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(target.artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (state.pickingPlaylist) {
                Text(
                    stringResource(R.string.ui_choose_playlist),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val list = state.playlists
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                } else if (list.isEmpty()) {
                    Text(stringResource(R.string.ui_no_editable_playlists), Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { viewModel.addToPlaylist(target, p); onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverImage(p.images.bestFor(120), null, Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                return@Column
            }

            val liked = state.isLiked
            ActionRow(
                icon = if (liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = stringResource(if (liked == true) R.string.ui_remove_liked else R.string.ui_add_liked),
                enabled = liked != null,
            ) { viewModel.toggleLike(target) }
            ActionRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.ui_add_queue)) {
                viewModel.addToQueue(target); onDismiss()
            }
            ActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.ui_add_playlist)) {
                viewModel.showPlaylistPicker()
            }
            if (target.removable && onRemoveFromPlaylist != null) {
                ActionRow(Icons.Rounded.Delete, stringResource(R.string.ui_remove_playlist)) {
                    onRemoveFromPlaylist(); onDismiss()
                }
            }
            target.artistId?.takeIf { it.isNotBlank() }?.let { id ->
                ActionRow(Icons.Rounded.Person, stringResource(R.string.ui_go_artist)) { onDismiss(); onGoToArtist(id) }
            }
            target.albumId?.takeIf { it.isNotBlank() }?.let { id ->
                ActionRow(Icons.Rounded.Album, stringResource(R.string.ui_go_album)) { onDismiss(); onGoToAlbum(id) }
            }
            ActionRow(Icons.Rounded.Share, stringResource(R.string.ui_share)) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${target.name} — ${target.artists}\nhttps://open.spotify.com/track/${target.id}")
                }
                context.startActivity(Intent.createChooser(send, null))
                onDismiss()
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
