package com.spotineil.feature.artist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.spotineil.core.designsystem.component.CarouselSection
import com.spotineil.core.designsystem.component.DetailScaffold
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MediaCard
import com.spotineil.core.designsystem.component.SectionHeader
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.model.Artist
import com.spotineil.core.model.ArtistDetails
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.ui.TrackActionTarget
import com.spotineil.core.ui.TrackActionsSheet
import com.spotineil.core.ui.toActionTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
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
data class ArtistRoute(val id: String)

fun NavController.navigateToArtist(id: String) = navigate(ArtistRoute(id))

fun NavGraphBuilder.artistScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    composable<ArtistRoute> { ArtistRoute(onBack, onArtistClick, onAlbumClick, onMessage) }
}

data class ArtistUiState(
    val artist: Artist? = null,
    val details: ArtistDetails? = null,
    val isFollowing: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ArtistRoute>().id

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    val nowPlayingUri: StateFlow<String?> = player.state.map { it.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() = viewModelScope.launch {
        catalog.cachedArtist(id)?.let { a -> _state.update { it.copy(artist = a) } }
        _state.update { it.copy(error = null) }
        runCatching { catalog.artist(id) }
            .onSuccess { d -> _state.update { it.copy(artist = d.artist, details = d, isFollowing = d.isFollowing) } }
            .onFailure { e -> _state.update { it.copy(error = if (it.artist == null) e else null) } }
    }

    fun toggleFollow() = viewModelScope.launch {
        val target = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = target) }
        runCatching { library.setFollowingArtist(id, target) }.onFailure { _state.update { s -> s.copy(isFollowing = !target) } }
    }

    fun playArtist(shuffle: Boolean) {
        val uri = _state.value.artist?.uri ?: return
        player.play(PlayRequest(contextUri = uri, shuffle = shuffle))
    }

    fun playTrack(uri: String) = player.play(PlayRequest(uri = uri))
}

@Composable
fun ArtistRoute(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingUri.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<TrackActionTarget?>(null) }
    var showAllTop by rememberSaveable { mutableStateOf(false) }
    val artist = state.artist
    val details = state.details

    DetailScaffold(
        title = artist?.name.orEmpty(),
        imageUrl = artist?.let { rememberImageUrl(it.images, 640) },
        imageShape = CircleShape,
        onBack = onBack,
        headerMeta = {
            if (artist != null) {
                Text(
                    stringResource(R.string.artist_followers, NumberFormat.getIntegerInstance().format(artist.followers)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actionRow = {
            if (artist != null) {
                OutlinedButton(onClick = viewModel::toggleFollow, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(if (state.isFollowing) R.string.artist_following else R.string.artist_follow))
                }
            }
        },
        onPlay = artist?.let { { viewModel.playArtist(shuffle = false) } },
        onShuffle = artist?.let { { viewModel.playArtist(shuffle = true) } },
    ) {
        when {
            details != null -> {
                if (details.topTracks.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.artist_popular)) }
                    val shown = if (showAllTop) details.topTracks else details.topTracks.take(5)
                    itemsIndexed(shown, key = { i, t -> "top:${t.id}:$i" }) { i, t ->
                        TrackRow(
                            t.name,
                            t.album?.name.orEmpty(),
                            onClick = { viewModel.playTrack(t.uri) },
                            imageUrl = rememberImageUrl(t.images, 120),
                            index = i + 1,
                            explicit = t.explicit,
                            isCurrent = t.uri == nowPlaying,
                            onMore = { target = t.toActionTarget() },
                        )
                    }
                    if (details.topTracks.size > 5) {
                        item {
                            TextButton(onClick = { showAllTop = !showAllTop }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(stringResource(if (showAllTop) R.string.artist_show_less else R.string.artist_see_more))
                            }
                        }
                    }
                }
                item {
                    CarouselSection(stringResource(R.string.artist_albums), details.albums, key = { it.id }) {
                        MediaCard(it.name, it.releaseYear, rememberImageUrl(it.images, 300), { onAlbumClick(it.id) })
                    }
                }
                item {
                    CarouselSection(stringResource(R.string.artist_singles), details.singles, key = { it.id }) {
                        MediaCard(it.name, it.releaseYear, rememberImageUrl(it.images, 300), { onAlbumClick(it.id) })
                    }
                }
                if (details.artist.genres.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            SectionHeader(stringResource(R.string.artist_about), Modifier.padding(start = 0.dp))
                            Text(
                                details.artist.genres.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            state.error != null -> item { ErrorState(state.error!!, onRetry = { viewModel.load() }) }
            else -> items(6) { SkeletonTrackRow() }
        }
    }

    target?.let { TrackActionsSheet(it, { target = null }, onArtistClick, onAlbumClick, onMessage) }
}
