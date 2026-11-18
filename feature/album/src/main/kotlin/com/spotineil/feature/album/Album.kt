package com.spotineil.feature.album

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.spotineil.core.data.repository.CatalogRepository
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.designsystem.component.DetailScaffold
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.model.Album
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.ui.TrackActionTarget
import com.spotineil.core.ui.TrackActionsSheet
import com.spotineil.core.ui.toActionTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AlbumRoute(val id: String)

fun NavController.navigateToAlbum(id: String) = navigate(AlbumRoute(id))

fun NavGraphBuilder.albumScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    composable<AlbumRoute> { AlbumRoute(onBack, onArtistClick, onAlbumClick, onMessage) }
}

data class AlbumUiState(
    val album: Album? = null,
    val isSaved: Boolean = false,
    val isLoading: Boolean = true,
    val error: Throwable? = null,
)

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<AlbumRoute>().id

    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    val nowPlayingUri: StateFlow<String?> = player.state.map { it.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() = viewModelScope.launch {
        // Offline-first: show the cached album immediately, then refresh.
        catalog.cachedAlbum(id)?.let { cached -> _state.update { it.copy(album = cached) } }
        _state.update { it.copy(isLoading = true, error = null) }
        runCatching { catalog.album(id) }
            .onSuccess { d -> _state.update { it.copy(album = d.album, isSaved = d.isSaved, isLoading = false) } }
            .onFailure { e -> _state.update { it.copy(isLoading = false, error = if (it.album == null) e else null) } }
    }

    fun toggleSaved() = viewModelScope.launch {
        val target = !_state.value.isSaved
        _state.update { it.copy(isSaved = target) }
        runCatching { library.setAlbumSaved(id, target) }.onFailure { _state.update { s -> s.copy(isSaved = !target) } }
    }

    fun play(index: Int? = null, shuffle: Boolean? = null) {
        val album = _state.value.album ?: return
        player.play(PlayRequest(contextUri = album.uri, index = index, shuffle = shuffle ?: if (index == null) false else null))
    }
}

@Composable
fun AlbumRoute(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingUri.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<TrackActionTarget?>(null) }
    val album = state.album

    DetailScaffold(
        title = album?.name.orEmpty(),
        imageUrl = album?.let { rememberImageUrl(it.images, 640) },
        onBack = onBack,
        headerMeta = {
            if (album != null) {
                Text(
                    album.artists.joinToString { it.name },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOfNotNull(
                        stringResource(R.string.album_type_album),
                        album.releaseYear,
                        stringResource(R.string.album_tracks, album.tracks.size),
                        formatDuration(album.tracks.sumOf { it.durationMs }),
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actionRow = {
            if (album != null) {
                IconButton(onClick = viewModel::toggleSaved) {
                    Icon(
                        if (state.isSaved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                        stringResource(if (state.isSaved) R.string.album_remove else R.string.album_save),
                        tint = if (state.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        onPlay = album?.let { { viewModel.play() } },
        onShuffle = album?.let { { viewModel.play(shuffle = true) } },
    ) {
        when {
            album != null -> {
                itemsIndexed(album.tracks, key = { i, t -> "${t.id}:$i" }) { i, t ->
                    TrackRow(
                        title = t.name,
                        subtitle = t.artistNames,
                        onClick = { viewModel.play(index = i) },
                        index = i + 1,
                        explicit = t.explicit,
                        isCurrent = t.uri == nowPlaying,
                        enabled = t.isPlayable,
                        onMore = { target = t.toActionTarget().copy(imageUrl = album.images.lastOrNull()?.url) },
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    album.label?.let {
                        Text(
                            "℗ $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            state.error != null -> item { ErrorState(state.error!!, onRetry = { viewModel.load() }) }
            else -> items(8) { SkeletonTrackRow() }
        }
    }

    target?.let { TrackActionsSheet(it, { target = null }, onArtistClick, onAlbumClick, onMessage) }
}
