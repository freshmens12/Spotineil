@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spotineil.feature.discover.free

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.view.ViewGroup
import com.spotineil.core.designsystem.theme.LocalPipState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.DefaultYouTubeRepository
import com.spotineil.core.data.repository.LastFmRepository
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.LyricsQuery
import com.spotineil.core.data.repository.LyricsRepository
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.data.repository.isBy
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.StreamTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.Lyrics
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.model.toPlayable
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.spotineil.core.model.YOUTUBE_MEDIA_PREFIX
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class YouTubePlayerUiState(
    val queue: List<YouTubeVideo> = emptyList(),
    val index: Int = 0,
    val isLiked: Boolean = false,
    val lyrics: Lyrics? = null,
) {
    val current: YouTubeVideo? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
}

/** What the player is busy with (shows a spinner on the matching button). */
enum class YouTubeBusy { None, Radio, Similar }

sealed interface YouTubePlayerEvent {
    data class Message(val text: Int, val arg: String? = null) : YouTubePlayerEvent
    /** Similar songs started on a free source: leave the YouTube screen. */
    data object LeftForFreeSource : YouTubePlayerEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YouTubePlayerViewModel @Inject constructor(
    private val freePlayer: FreePlayer,
    private val liked: LikedMediaRepository,
    private val lyricsRepository: LyricsRepository,
    private val youTube: YouTubeRepository,
    private val lastFm: LastFmRepository,
    private val downloader: com.spotineil.core.player.youtube.YouTubeDownloader,
) : ViewModel() {

    private val _busy = MutableStateFlow(YouTubeBusy.None)
    val busy: StateFlow<YouTubeBusy> = _busy.asStateFlow()
    private val _events = Channel<YouTubePlayerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The last song ended while Up next was still being fetched: play on when it arrives. */
    private var waitingForNext = false

    private val currentVideo = combine(freePlayer.youTubeQueue, freePlayer.youTubeIndex) { q, i -> q.getOrNull(i) }
        .distinctUntilChanged()

    private val lyrics = currentVideo.mapLatest { v ->
        v ?: return@mapLatest null
        // Lyrics are matched by song, artist and length.
        runCatching { lyricsRepository.lyrics(LyricsQuery(v.songTitle, v.artist, null, v.durationMs)) }.getOrNull()
    }

    private val isLiked = currentVideo.flatMapLatest { v -> if (v == null) flowOf(false) else liked.isLiked(v.toPlayable().mediaId) }

    val state: StateFlow<YouTubePlayerUiState> = combine(freePlayer.youTubeQueue, freePlayer.youTubeIndex, isLiked, lyrics) { q, i, l, ly ->
        YouTubePlayerUiState(q, i, l, ly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouTubePlayerUiState(freePlayer.youTubeQueue.value, freePlayer.youTubeIndex.value))

    /** Position of the current song in the audio player (0 while another item is playing). */
    val positionMs: StateFlow<Long> = flow {
        while (true) {
            val playback = freePlayer.playback.value
            val id = state.value.current?.let { YOUTUBE_MEDIA_PREFIX + it.id }
            emit(if (id != null && playback.track?.uri == id) playback.estimatedPosition(System.currentTimeMillis()) else 0L)
            delay(500)
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val isPlaying: StateFlow<Boolean> = freePlayer.playback.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val durationMs: StateFlow<Long> = freePlayer.playback.map { it.track?.durationMs ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var lastSavedAt = 0L

    init {
        // Play on as soon as the next songs arrive after the last one ended.
        viewModelScope.launch {
            state.collect {
                if (waitingForNext && it.hasNext) {
                    waitingForNext = false
                    freePlayer.nextYouTube()
                }
            }
        }
        // Remember where we are every few seconds ("Continue listening").
        viewModelScope.launch {
            positionMs.collect { ms ->
                if (kotlin.math.abs(ms - lastSavedAt) >= SAVE_EVERY_MS) {
                    lastSavedAt = ms
                    freePlayer.saveYouTubePosition(ms)
                }
            }
        }
    }

    fun saveNow() = freePlayer.saveYouTubePosition(positionMs.value)


    /** Where to start [videoId]: the remembered position when continuing, else the beginning. */
    fun startPositionMs(videoId: String): Long = freePlayer.takeResumePosition(videoId)

    fun togglePlayPause() = freePlayer.togglePlayPause()
    fun seekTo(ms: Long) = freePlayer.seekTo(ms)

    fun next() {
        if (state.value.hasNext) freePlayer.nextYouTube() else waitingForNext = true
    }
    fun previous() = freePlayer.previousYouTube()
    fun select(i: Int) {
        waitingForNext = false
        lastSavedAt = 0
        freePlayer.selectYouTube(i)
    }

    /** Saves the song as an audio file in Music/Spotineil. */
    fun download() {
        val v = state.value.current ?: return
        downloader.download(v.toPlayable())
    }

    fun toggleLike() {
        val v = state.value.current ?: return
        viewModelScope.launch { liked.setLiked(v.toPlayable(), !state.value.isLiked) }
    }

    /** "Start radio": replace Up next with songs by this artist and similar artists. */
    fun startRadio() {
        val seed = state.value.current ?: return
        if (_busy.value != YouTubeBusy.None) return
        viewModelScope.launch { loadRadio(seed, manual = true) }
    }

    private suspend fun loadRadio(seed: YouTubeVideo, manual: Boolean) {
        if (manual) _busy.value = YouTubeBusy.Radio
        try {
            val similar = runCatching { lastFm.similarArtists(seed.artist, limit = 6) }.getOrDefault(emptyList())
            val songs = youTube.radio(seed, similar.take(DefaultYouTubeRepository.RADIO_ARTISTS - 1))
            if (songs.isEmpty()) {
                if (manual) _events.send(YouTubePlayerEvent.Message(R.string.yt_radio_empty))
                return
            }
            // Only replace Up next if we're still on the song the station was made for.
            if (freePlayer.youTubeQueue.value.getOrNull(freePlayer.youTubeIndex.value)?.id != seed.id) return
            freePlayer.setYouTubeUpNext(songs)
            if (manual) _events.send(YouTubePlayerEvent.Message(R.string.yt_radio_started, seed.artist))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (manual) _events.send(YouTubePlayerEvent.Message(R.string.yt_radio_empty))
        } finally {
            if (manual) _busy.value = YouTubeBusy.None
        }
    }

    /**
     * "Play similar": a new queue of songs by artists that sound like this one (never the same
     * artist again), starting right away. Use "Start radio" to also keep this artist's songs.
     */
    fun playSimilar() {
        val seed = state.value.current ?: return
        if (_busy.value != YouTubeBusy.None) return
        viewModelScope.launch {
            _busy.value = YouTubeBusy.Similar
            try {
                val similar = runCatching { lastFm.similarArtists(seed.artist, limit = 8) }.getOrDefault(emptyList())
                    .filterNot { it.equals(seed.artist, ignoreCase = true) }
                val songs = if (similar.isEmpty()) emptyList() else {
                    youTube.radio(seed, similar.take(DefaultYouTubeRepository.RADIO_ARTISTS))
                        .filterNot { it.isBy(seed.artist) }
                        .take(40)
                }
                if (songs.isEmpty()) {
                    _events.send(YouTubePlayerEvent.Message(R.string.yt_similar_empty))
                    return@launch
                }
                freePlayer.playYouTube(songs, 0)
                _events.send(YouTubePlayerEvent.Message(R.string.yt_similar_started, seed.artist))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _events.send(YouTubePlayerEvent.Message(R.string.yt_similar_empty))
            } finally {
                _busy.value = YouTubeBusy.None
            }
        }
    }

    private companion object {
        const val SAVE_EVERY_MS = 5_000L
    }
}

@Composable
fun YouTubePlayerScreen(nav: FreeNavigation, viewModel: YouTubePlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val duration by viewModel.durationMs.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()
    val video = state.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is YouTubePlayerEvent.Message -> nav.onMessage(context.getString(event.text, event.arg.orEmpty()))
                YouTubePlayerEvent.LeftForFreeSource -> nav.onBack()
            }
        }
    }

    // Continuing a saved session: jump to where it stopped.
    LaunchedEffect(video?.id) {
        val v = video ?: return@LaunchedEffect
        val startMs = viewModel.startPositionMs(v.id)
        if (startMs > 0) viewModel.seekTo(startMs)
    }

    // Leaving the screen: remember the spot for "Continue listening".
    DisposableEffect(Unit) { onDispose { viewModel.saveNow() } }

    // Like music apps: Previous restarts the song after the first 3 seconds.
    val canRestart = position > RESTART_THRESHOLD_MS

    val background = MaterialTheme.colorScheme.background
    val dominant by rememberDominantColor(video?.thumbnailUrl, MaterialTheme.colorScheme.surfaceContainerHigh)
    var showAllLyrics by remember { mutableStateOf(false) }
    val saveLyrics = com.spotineil.core.ui.rememberLyricsSaver()
    var addToPlaylist by remember { mutableStateOf(false) }
    if (addToPlaylist && video != null) {
        com.spotineil.core.ui.AddToPlaylistSheet(listOf(video.toPlayable()), onDismiss = { addToPlaylist = false }, onMessage = nav.onMessage)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dominant.copy(alpha = 0.85f), background, background))),
    ) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Text(stringResource(R.string.yt_now_playing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            // Inside the scrolling area: a pinned cover would sit on top of the lyrics as they scroll by.
            ArtworkFrame(video, modifier = Modifier.padding(top = 8.dp))

            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        video?.songTitle.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        video?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.clickable(enabled = video != null) { video?.let { nav.onArtist(it.artist, null, null) } },
                    )
                }
                IconButton(onClick = { addToPlaylist = true }, enabled = video != null) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.yt_add_to_playlist))
                }
                IconButton(onClick = viewModel::download, enabled = video != null, modifier = Modifier.testTag("yt_download")) {
                    Icon(Icons.Rounded.Download, stringResource(com.spotineil.core.ui.R.string.ui_download))
                }
                IconButton(onClick = viewModel::toggleLike, modifier = Modifier.testTag("yt_like")) {
                    Icon(
                        if (state.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        stringResource(if (state.isLiked) R.string.yt_unlike else R.string.yt_like),
                        tint = if (state.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SeekBar(position, duration.takeIf { it > 0 } ?: video?.durationMs ?: 0L, viewModel::seekTo)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::previous, enabled = state.hasPrevious || canRestart, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.yt_previous), Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.size(64.dp).testTag("yt_play_pause"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (playing) R.string.yt_pause else R.string.free_play),
                        Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
                IconButton(onClick = viewModel::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.yt_next), Modifier.size(36.dp))
                }
            }

            // Start radio (same + similar artists) and Play similar (more songs in the same style).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayerActionButton(
                    icon = Icons.Rounded.Radio,
                    label = stringResource(R.string.yt_start_radio),
                    loading = busy == YouTubeBusy.Radio,
                    enabled = busy == YouTubeBusy.None && video != null,
                    onClick = viewModel::startRadio,
                    modifier = Modifier.weight(1f).testTag("yt_start_radio"),
                )
                PlayerActionButton(
                    icon = Icons.Rounded.Headphones,
                    label = stringResource(R.string.yt_play_similar),
                    loading = busy == YouTubeBusy.Similar,
                    enabled = busy == YouTubeBusy.None && video != null,
                    onClick = viewModel::playSimilar,
                    modifier = Modifier.weight(1f).testTag("yt_play_similar"),
                )
            }

            state.lyrics?.takeIf { !it.isInstrumental && it.lines.isNotEmpty() }?.let { lyrics ->
                YouTubeLyricsCard(
                    lyrics, position, expanded = showAllLyrics,
                    onDownload = { video?.let { saveLyrics(lyrics, it.songTitle, it.artist) } },
                ) { showAllLyrics = !showAllLyrics }
            }
            if (state.queue.size > 1) {
                Text(
                    stringResource(R.string.yt_up_next),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                state.queue.forEachIndexed { i, v ->
                    YouTubeRow(v, isCurrent = i == state.index, onClick = { viewModel.select(i) })
                }
            }
        }
    }
}

/** The album art: a square cover. */
@Composable
private fun ArtworkFrame(video: YouTubeVideo?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = (maxWidth * 0.82f).coerceAtMost(340.dp)
        CoverImage(video?.thumbnailUrl, null, Modifier.size(side), shape = RoundedCornerShape(10.dp))
    }
}


private const val RESTART_THRESHOLD_MS = 3_000L

@Composable
private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, null, Modifier.size(18.dp))
        }
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            ),
            modifier = Modifier.testTag("yt_seek"),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(formatDuration((fraction * durationMs).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun YouTubeLyricsCard(lyrics: Lyrics, positionMs: Long, expanded: Boolean, onDownload: () -> Unit, onToggle: () -> Unit) {
    val current = if (lyrics.isSynced) lyrics.indexAt(positionMs).coerceAtLeast(0) else 0
    val lines = if (expanded) lyrics.lines else lyrics.lines.drop(current).filter { it.text.isNotBlank() }.take(2)
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("yt_lyrics"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.yt_lyrics), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDownload, modifier = Modifier.testTag("yt_lyrics_download")) {
                    Icon(Icons.Rounded.Download, stringResource(com.spotineil.core.ui.R.string.lyrics_download), Modifier.size(20.dp))
                }
            }
            // Same look as the full lyrics view: big lines with room between them, whole lines (no cut-off).
            lines.forEachIndexed { i, line ->
                val active = if (expanded) lyrics.isSynced && i == current else i == 0
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active || !lyrics.isSynced) 1f else 0.5f),
                    modifier = Modifier.padding(top = 10.dp, end = 12.dp),
                )
            }
        }
    }
}

/** A YouTube song in a list: thumbnail, title, "artist • YouTube • 3:45". */
@Composable
internal fun YouTubeRow(video: YouTubeVideo, isCurrent: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val longClick = onLongClick ?: com.spotineil.core.ui.LocalSongMenu.current?.let { open -> { open(video.toPlayable()) } }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = longClick).padding(start = 16.dp, end = if (longClick != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp).testTag("yt_${video.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CoverImage(video.thumbnailUrl, null, Modifier.size(48.dp))
            if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                video.songTitle,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    video.artist,
                    stringResource(if (video.isTopic) R.string.yt_kind_song else R.string.yt_kind_video),
                    if (video.durationMs > 0) formatDuration(video.durationMs) else "",
                ).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        com.spotineil.core.ui.SongMenuButton(longClick)
    }
}
