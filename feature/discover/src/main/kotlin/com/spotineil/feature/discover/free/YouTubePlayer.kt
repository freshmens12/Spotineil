package com.spotineil.feature.discover.free

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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
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
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.LyricsQuery
import com.spotineil.core.data.repository.LyricsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YouTubePlayerViewModel @Inject constructor(
    private val freePlayer: FreePlayer,
    private val liked: LikedMediaRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    private val currentVideo = combine(freePlayer.youTubeQueue, freePlayer.youTubeIndex) { q, i -> q.getOrNull(i) }
        .distinctUntilChanged()

    private val lyrics = currentVideo.mapLatest { v ->
        v ?: return@mapLatest null
        // Lyrics come from LRCLIB at play time; matched by song, artist and length.
        runCatching { lyricsRepository.lyrics(LyricsQuery(v.songTitle, v.artist, null, v.durationMs)) }.getOrNull()
    }

    private val isLiked = currentVideo.flatMapLatest { v -> if (v == null) flowOf(false) else liked.isLiked(v.toPlayable().mediaId) }

    val state: StateFlow<YouTubePlayerUiState> = combine(freePlayer.youTubeQueue, freePlayer.youTubeIndex, isLiked, lyrics) { q, i, l, ly ->
        YouTubePlayerUiState(q, i, l, ly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouTubePlayerUiState(freePlayer.youTubeQueue.value, freePlayer.youTubeIndex.value))

    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _playing.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _duration.asStateFlow()

    fun onPosition(ms: Long) { _position.value = ms }
    fun onDuration(ms: Long) { _duration.value = ms }
    fun onPlaying(playing: Boolean) { _playing.value = playing }

    fun next() { if (state.value.hasNext) select(state.value.index + 1) }
    fun previous() { if (state.value.hasPrevious) select(state.value.index - 1) }
    fun select(i: Int) {
        _position.value = 0
        _duration.value = 0
        freePlayer.selectYouTube(i)
    }

    fun toggleLike() {
        val v = state.value.current ?: return
        viewModelScope.launch { liked.setLiked(v.toPlayable(), !state.value.isLiked) }
    }
}

@Composable
fun YouTubePlayerScreen(nav: FreeNavigation, viewModel: YouTubePlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val duration by viewModel.durationMs.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()
    val video = state.current
    val context = LocalContext.current
    val pip = LocalPipState.current
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }

    // One player view for the whole screen, so switching between the normal layout and the PiP
    // window never reloads the song.
    val playerView = rememberOfficialYouTubePlayer(
        onReady = { player = it },
        onEnded = viewModel::next,
        onError = { error ->
            nav.onMessage(context.getString(errorRes(error)))
            viewModel.next()
        },
        onSecond = { viewModel.onPosition((it * 1000).toLong()) },
        onDuration = { viewModel.onDuration((it * 1000).toLong()) },
        onPlaying = viewModel::onPlaying,
    )

    // Load the selected video into YouTube's player whenever the queue position changes.
    LaunchedEffect(player, video?.id) {
        val p = player ?: return@LaunchedEffect
        video?.let { p.loadVideo(it.id, 0f) }
    }

    // Offer picture-in-picture while this screen (and so the video) is showing.
    val playPause: () -> Unit = { player?.let { if (playing) it.pause() else it.play() } }
    DisposableEffect(pip) {
        pip.eligible = true
        onDispose {
            pip.eligible = false
            pip.onPlayPause = {}
            pip.onNext = {}
        }
    }
    pip.onPlayPause = playPause
    pip.onNext = viewModel::next
    LaunchedEffect(playing, state.hasNext) {
        pip.isPlaying = playing
        pip.hasNext = state.hasNext
    }

    val background = MaterialTheme.colorScheme.background
    val dominant by rememberDominantColor(video?.thumbnailUrl, MaterialTheme.colorScheme.surfaceContainerHigh)
    var showAllLyrics by remember { mutableStateOf(false) }
    // Where the album-art square is, so the video can be laid over it. The video view itself
    // always stays in the same place in the UI tree (moving it would detach the web player and
    // pause the song, e.g. when the PiP window opens).
    var artRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var rootOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
    if (pip.isInPip) {
        // The floating window shows the video only (YouTube's player stays visible).
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dominant.copy(alpha = 0.85f), background, background))),
    ) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Text(stringResource(R.string.yt_now_playing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        // "Album art": the video, framed in a square over its blurred thumbnail. It never scrolls
        // away, so YouTube's player is always visible.
        ArtworkFrame(video, onPlaced = { artRect = it }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        video?.songTitle.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(video?.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = viewModel::toggleLike, modifier = Modifier.testTag("yt_like")) {
                    Icon(
                        if (state.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        stringResource(if (state.isLiked) R.string.yt_unlike else R.string.yt_like),
                        tint = if (state.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SeekBar(position, duration.takeIf { it > 0 } ?: video?.durationMs ?: 0L) { ms ->
                player?.seekTo(ms / 1000f)
                viewModel.onPosition(ms)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::previous, enabled = state.hasPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.yt_previous), Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = playPause,
                    modifier = Modifier.size(64.dp).testTag("yt_play_pause"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (playing) R.string.yt_pause else R.string.free_play),
                        Modifier.size(36.dp),
                        tint = background,
                    )
                }
                IconButton(onClick = viewModel::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.yt_next), Modifier.size(36.dp))
                }
            }

            state.lyrics?.takeIf { !it.isInstrumental && it.lines.isNotEmpty() }?.let { lyrics ->
                YouTubeLyricsCard(lyrics, position, expanded = showAllLyrics) { showAllLyrics = !showAllLyrics }
            }
            Text(
                stringResource(R.string.yt_player_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
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

    // The one and only video view: over the album-art square normally, the whole window in PiP.
    val videoModifier = when {
        pip.isInPip -> Modifier.fillMaxSize()
        artRect != null -> {
            val r = artRect!!
            val h = r.width * 9f / 16f
            Modifier
                .offset { IntOffset((r.left - rootOffset.x).roundToInt(), (r.top - rootOffset.y + (r.height - h) / 2f).roundToInt()) }
                .size(with(density) { r.width.toDp() }, with(density) { h.toDp() })
        }
        else -> Modifier.size(1.dp)
    }
    VideoSurface(playerView, videoModifier.testTag("yt_player"))
    }
}

/**
 * The album-art spot: a square with the video's blurred thumbnail; the video is laid over its
 * middle. Sized so the 16:9 video is never under 200 x 200 px (YouTube's minimum player size).
 */
@Composable
private fun ArtworkFrame(video: YouTubeVideo?, onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // A 16:9 video at least 200 px tall must be at least 356 px wide.
    val minSide = with(density) { 356.toDp() }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = (maxWidth * 0.82f).coerceAtMost(340.dp).coerceAtLeast(minSide)
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .onGloballyPositioned { onPlaced(it.boundsInRoot()) },
        ) {
            CoverImage(
                video?.thumbnailUrl,
                null,
                Modifier.fillMaxSize().blur(24.dp).alpha(0.55f),
                shape = androidx.compose.ui.graphics.RectangleShape,
            )
        }
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

/** Message for a YouTube player error (the player then skips to the next song). */
private fun errorRes(error: PlayerConstants.PlayerError): Int = when (error) {
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> R.string.yt_error_embed
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> R.string.yt_error_missing
    else -> R.string.yt_error_generic
}

/**
 * YouTube's official IFrame player. It follows the activity lifecycle: it keeps playing while the
 * app is visible (including the PiP window) and pauses when the app is stopped, i.e. when the PiP
 * window is closed or the screen turns off. Background playback is not enabled, as YouTube's terms
 * require. YouTube's own control bar is hidden (controls=0, an official player option); the screen
 * provides play/pause, seek and next instead.
 */
@Composable
private fun rememberOfficialYouTubePlayer(
    onReady: (YouTubePlayer) -> Unit,
    onEnded: () -> Unit,
    onError: (PlayerConstants.PlayerError) -> Unit,
    onSecond: (Float) -> Unit,
    onDuration: (Float) -> Unit,
    onPlaying: (Boolean) -> Unit,
): YouTubePlayerView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    val second by rememberUpdatedState(onSecond)
    val length by rememberUpdatedState(onDuration)
    val playingChanged by rememberUpdatedState(onPlaying)
    val ready by rememberUpdatedState(onReady)

    val view = remember {
        YouTubePlayerView(context).apply {
            enableAutomaticInitialization = false
            val options = IFramePlayerOptions.Builder(context)
                .controls(0)
                .rel(0)
                .ivLoadPolicy(3)
                .fullscreen(0)
                .build()
            initialize(
                object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) = ready(youTubePlayer)
                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        playingChanged(state == PlayerConstants.PlayerState.PLAYING || state == PlayerConstants.PlayerState.BUFFERING)
                        if (state == PlayerConstants.PlayerState.ENDED) ended()
                    }
                    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) = second(second)
                    override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) = length(duration)
                    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                        failed(error)
                    }
                },
                options,
            )
        }
    }
    DisposableEffect(lifecycle, view) {
        lifecycle.addObserver(view)
        onDispose {
            lifecycle.removeObserver(view)
            view.release()
        }
    }
    return view
}

/** Hosts the player view (kept in one place in the UI tree for the whole screen). */
@Composable
private fun VideoSurface(view: YouTubePlayerView, modifier: Modifier = Modifier) {
    AndroidView(factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier)
}

@Composable
private fun YouTubeLyricsCard(lyrics: Lyrics, positionMs: Long, expanded: Boolean, onToggle: () -> Unit) {
    val current = if (lyrics.isSynced) lyrics.indexAt(positionMs).coerceAtLeast(0) else 0
    val lines = if (expanded) lyrics.lines else lyrics.lines.drop(current).filter { it.text.isNotBlank() }.take(2)
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("yt_lyrics"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.yt_lyrics), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            lines.forEachIndexed { i, line ->
                val active = if (expanded) lyrics.isSynced && i == current else i == 0
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active || !lyrics.isSynced) 1f else 0.5f),
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A YouTube song in a list: thumbnail, title, "artist • YouTube • 3:45". */
@Composable
internal fun YouTubeRow(video: YouTubeVideo, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp).testTag("yt_${video.id}"),
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
                listOf(video.artist, "YouTube", formatDuration(video.durationMs)).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
