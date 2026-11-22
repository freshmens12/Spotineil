package com.spotineil.feature.discover.free

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.data.spotifyweb.SpotifyPlaylist
import com.spotineil.core.data.spotifyweb.SpotifyTrack
import com.spotineil.core.data.spotifyweb.SpotifyWebAuth
import com.spotineil.core.data.spotifyweb.SpotifyWebException
import com.spotineil.core.data.spotifyweb.SpotifyWebRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object SpotifyLibraryRoute
@Serializable data object SpotifyConnectRoute
/** [id] is a playlist id, or [LIKED_ID] for Liked Songs. */
@Serializable data class SpotifyPlaylistRoute(val id: String, val name: String)

const val SPOTIFY_LIKED_ID = "liked"

fun NavGraphBuilder.spotifyScreens(nav: FreeNavigation) {
    composable<SpotifyLibraryRoute> { SpotifyLibraryScreen(nav) }
    composable<SpotifyConnectRoute> { SpotifyConnectScreen(onDone = nav.onBack, onBack = nav.onBack) }
    composable<SpotifyPlaylistRoute> { SpotifyPlaylistScreen(nav) }
}

// ---------------------------------------------------------------------------------------------
// Connect (sign in to Spotify's web player)
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class SpotifyConnectViewModel @Inject constructor(
    val auth: SpotifyWebAuth,
    private val repository: SpotifyWebRepository,
) : ViewModel() {
    private var checkedToken: String? = null

    /** Checks the token the web player showed; true once it belongs to a real account. */
    suspend fun checkSignedIn(): Boolean {
        val token = auth.latestToken() ?: return false
        if (token == checkedToken) return false
        checkedToken = token
        val name = runCatching { repository.displayName(token) }.getOrNull() ?: return false
        auth.markConnected(name)
        return true
    }
}

/** Spotify's own sign-in page. [onDone] runs once the account is connected. */
@Composable
fun SpotifyConnectScreen(onDone: () -> Unit, onBack: () -> Unit, viewModel: SpotifyConnectViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val webView = remember { viewModel.auth.newWebView(context) }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    var signedIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (!signedIn) {
            if (viewModel.checkSignedIn()) {
                signedIn = true
                onDone()
            }
            delay(1_500)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Text(stringResource(R.string.spotify_connect_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.spotify_connect_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        AndroidView(
            factory = {
                webView.apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    loadUrl(SpotifyWebAuth.LOGIN_URL)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = LocalBottomContentPadding.current),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Library: Liked Songs + playlists
// ---------------------------------------------------------------------------------------------

sealed interface SpotifyLibraryState {
    data object NeedsConnect : SpotifyLibraryState
    data object Loading : SpotifyLibraryState
    data class Loaded(val playlists: List<SpotifyPlaylist>) : SpotifyLibraryState
    data class Failed(val message: String, val needsReconnect: Boolean) : SpotifyLibraryState
}

@HiltViewModel
class SpotifyLibraryViewModel @Inject constructor(
    private val auth: SpotifyWebAuth,
    private val repository: SpotifyWebRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SpotifyLibraryState>(SpotifyLibraryState.Loading)
    val state: StateFlow<SpotifyLibraryState> = _state.asStateFlow()
    val accountName: StateFlow<String?> = auth.accountName
    private var job: Job? = null

    init {
        viewModelScope.launch { auth.connected.collect { load() } }
    }

    fun load() {
        job?.cancel()
        if (!auth.connected.value) {
            _state.value = SpotifyLibraryState.NeedsConnect
            return
        }
        _state.value = SpotifyLibraryState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                SpotifyLibraryState.Loaded(repository.playlists())
            } catch (e: SpotifyWebException) {
                SpotifyLibraryState.Failed(e.message.orEmpty(), e.needsReconnect)
            }
        }
    }
}

@Composable
fun SpotifyLibraryScreen(nav: FreeNavigation, viewModel: SpotifyLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.accountName.collectAsStateWithLifecycle()
    val likedName = stringResource(R.string.free_liked_songs)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Column {
                Text(stringResource(R.string.spotify_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                account?.let { Text(stringResource(R.string.spotify_signed_in_as, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                SpotifyLibraryState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                SpotifyLibraryState.NeedsConnect -> MessageState(
                    Icons.Rounded.LibraryMusic,
                    stringResource(R.string.spotify_connect_title),
                    stringResource(R.string.spotify_connect_body),
                    actionLabel = stringResource(R.string.spotify_connect_button),
                    onAction = nav.onSpotifyConnect,
                )
                is SpotifyLibraryState.Failed -> MessageState(
                    Icons.Rounded.LibraryMusic,
                    s.message,
                    if (s.needsReconnect) stringResource(R.string.spotify_reconnect_hint) else null,
                    actionLabel = stringResource(if (s.needsReconnect) R.string.spotify_connect_button else R.string.spotify_retry),
                    onAction = if (s.needsReconnect) nav.onSpotifyConnect else viewModel::load,
                )
                is SpotifyLibraryState.Loaded -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                    item(key = "liked") {
                        LibraryRow(
                            stringResource(R.string.free_liked_songs),
                            stringResource(R.string.spotify_liked_subtitle),
                            { nav.onSpotifyPlaylist(SPOTIFY_LIKED_ID, likedName) },
                        ) { m -> LikedArt(m) }
                    }
                    items(s.playlists.size, key = { s.playlists[it].id }) { i ->
                        val p = s.playlists[i]
                        LibraryRow(
                            p.name,
                            listOf(stringResource(R.string.spotify_playlist_songs, p.trackCount), p.owner).filter { it.isNotBlank() }.joinToString(" • "),
                            { nav.onSpotifyPlaylist(p.id, p.name) },
                        ) { m -> CoverImage(p.imageUrl, null, m, placeholderIcon = Icons.Rounded.LibraryMusic) }
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// One playlist (or Liked Songs): the songs, played through YouTube Music
// ---------------------------------------------------------------------------------------------

sealed interface SpotifyTracksState {
    data object Loading : SpotifyTracksState
    data class Loaded(val tracks: List<SpotifyTrack>) : SpotifyTracksState
    data class Failed(val message: String, val needsReconnect: Boolean) : SpotifyTracksState
}

sealed interface SpotifyPlayEvent {
    data object OpenPlayer : SpotifyPlayEvent
    data class Message(val resId: Int) : SpotifyPlayEvent
}

@HiltViewModel
class SpotifyPlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SpotifyWebRepository,
    private val youTube: YouTubeRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    private val route = savedState.toRoute<SpotifyPlaylistRoute>()
    val title = route.name

    private val _state = MutableStateFlow<SpotifyTracksState>(SpotifyTracksState.Loading)
    val state: StateFlow<SpotifyTracksState> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _events = kotlinx.coroutines.channels.Channel<SpotifyPlayEvent>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var playJob: Job? = null

    init { load() }

    fun load() {
        _state.value = SpotifyTracksState.Loading
        viewModelScope.launch {
            _state.value = try {
                SpotifyTracksState.Loaded(
                    if (route.id == SPOTIFY_LIKED_ID) repository.likedSongs() else repository.playlistTracks(route.id),
                )
            } catch (e: SpotifyWebException) {
                SpotifyTracksState.Failed(e.message.orEmpty(), e.needsReconnect)
            }
        }
    }

    /**
     * Spotify's audio cannot be played outside Spotify, so each song is looked up on YouTube Music.
     * The tapped song and the next few start right away; the rest of the list joins the queue as
     * it is found.
     */
    fun play(tracks: List<SpotifyTrack>, index: Int) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _busy.value = true
            try {
                val window = tracks.drop(index).take(PLAY_WINDOW)
                val head = window.take(FIRST_BATCH).resolve()
                if (head.isEmpty()) {
                    _events.send(SpotifyPlayEvent.Message(R.string.spotify_not_found))
                    return@launch
                }
                freePlayer.playYouTube(head, 0)
                _events.send(SpotifyPlayEvent.OpenPlayer)
                _busy.value = false
                val all = head.toMutableList()
                for (batch in window.drop(FIRST_BATCH).chunked(PARALLEL)) {
                    val found = batch.resolve()
                    // Stop if something else has been started in the meantime.
                    if (freePlayer.youTubeQueue.value.none { it.id == head.first().id }) return@launch
                    all += found
                    freePlayer.setYouTubeUpNext(all.toList())
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun List<SpotifyTrack>.resolve(): List<YouTubeVideo> = coroutineScope {
        map { t -> async { runCatching { match(t) }.getOrNull() } }.awaitAll().filterNotNull().distinctBy { it.id }
    }

    /** The YouTube Music version of [track]: same length, studio recording, matching title and artist. */
    private suspend fun match(track: SpotifyTrack): YouTubeVideo? {
        val results = youTube.search("${track.title} ${track.artist.substringBefore(',')}")
        val title = normalize(track.title)
        val artist = normalize(track.artist.substringBefore(','))
        return results.withIndex().maxByOrNull { (i, v) ->
            var score = 0
            if (track.durationMs > 0 && v.durationMs > 0 && abs(v.durationMs - track.durationMs) <= 6_000) score += 4
            val found = normalize(v.songTitle)
            if (found.contains(title) || title.contains(found)) score += 3
            if (v.isTopic) score += 2
            if (normalize(v.artist).contains(artist) || normalize(v.channel).contains(artist)) score += 1
            score * 10 - i // earlier results win ties
        }?.value
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]|[^\\p{L}\\p{N}]+"), " ").trim()

    private companion object {
        const val PLAY_WINDOW = 60
        const val FIRST_BATCH = 3
        const val PARALLEL = 4
    }
}

@Composable
fun SpotifyPlaylistScreen(nav: FreeNavigation, viewModel: SpotifyPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SpotifyPlayEvent.OpenPlayer -> nav.onYouTubePlayer()
                is SpotifyPlayEvent.Message -> nav.onMessage(context.getString(event.resId))
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Text(viewModel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                SpotifyTracksState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SpotifyTracksState.Failed -> MessageState(
                    Icons.Rounded.LibraryMusic,
                    s.message,
                    null,
                    actionLabel = stringResource(if (s.needsReconnect) R.string.spotify_connect_button else R.string.spotify_retry),
                    onAction = if (s.needsReconnect) nav.onSpotifyConnect else viewModel::load,
                )
                is SpotifyTracksState.Loaded -> if (s.tracks.isEmpty()) {
                    MessageState(Icons.Rounded.LibraryMusic, stringResource(R.string.free_no_tracks), null)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                        item(key = "actions") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.play(s.tracks, 0) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.free_play), Modifier.padding(start = 6.dp))
                                }
                                OutlinedButton(onClick = { viewModel.play(s.tracks.shuffled(), 0) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                                    Text(stringResource(R.string.free_shuffle), Modifier.padding(start = 6.dp))
                                }
                            }
                            Text(
                                stringResource(R.string.spotify_played_from_youtube),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        itemsIndexed(s.tracks, key = { i, t -> "${t.id}_$i" }) { i, t ->
                            TrackRow(
                                t.title,
                                listOf(t.artist, formatDuration(t.durationMs)).filter { it.isNotBlank() }.joinToString(" • "),
                                onClick = { if (!busy) viewModel.play(s.tracks, i) },
                                imageUrl = t.imageUrl,
                            )
                        }
                    }
                }
            }
        }
    }
}
