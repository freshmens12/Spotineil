package com.spotineil.feature.player

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Mini player docked above the bottom navigation that expands into the full player with a
 * shared-element transition on the artwork and title.
 *
 * @param miniPlayerModifier positions the collapsed player (e.g. bottom padding for the nav bar).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerHost(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    miniPlayerModifier: Modifier = Modifier,
    /** True to open on the lyrics (the line on another Now playing screen was tapped). */
    openLyrics: Boolean = false,
    onLyricsOpened: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var karaoke by rememberSaveable { mutableStateOf(false) }
    var carMode by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(openLyrics) {
        if (openLyrics) {
            showLyrics = true
            onLyricsOpened()
        }
    }

    if (!state.hasTrack) return
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    val actions = PlayerActions(
        onTogglePlay = viewModel::togglePlayPause,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onSeek = viewModel::seekTo,
        onShuffle = viewModel::toggleShuffle,
        onRepeat = viewModel::cycleRepeat,
        onLike = viewModel::toggleLike,
        onCollapse = { onExpandedChange(false) },
        onExpand = { onExpandedChange(true) },
        onShowQueue = { sheet = "queue" },
        onShowDevices = { sheet = "devices" },
        onShowSleepTimer = { sheet = "sleep" },
        onToggleLyrics = { showLyrics = !showLyrics },
        onAddToPlaylist = state.track?.media?.takeIf { !it.isLive }?.let { { sheet = "playlist" } },
        onCarMode = { carMode = true },
        onArtistClick = {
            state.track?.artistUri?.substringAfterLast(':')?.let { onExpandedChange(false); onArtistClick(it) }
        },
        onAlbumClick = {
            state.track?.albumUri?.substringAfterLast(':')?.let { onExpandedChange(false); onAlbumClick(it) }
        },
    )

    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) },
            label = "player",
        ) { isExpanded ->
            val artwork = Modifier.sharedElement(
                rememberSharedContentState(key = "artwork"),
                animatedVisibilityScope = this@AnimatedContent,
                boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 380f) },
            )
            val title = Modifier.sharedBounds(
                rememberSharedContentState(key = "title"),
                animatedVisibilityScope = this@AnimatedContent,
            )
            if (isExpanded) {
                FullPlayer(
                    state = state,
                    actions = actions,
                    showLyrics = showLyrics,
                    lyricsContent = {
                        val lyrics by lyricsViewModel.state.collectAsStateWithLifecycle()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val saveLyrics = com.spotineil.core.ui.rememberLyricsSaver()
                        LyricsPanel(
                            lyrics,
                            state.positionMs,
                            viewModel::seekTo,
                            onShareLine = { line -> shareLyricLine(context, line, state.track?.name.orEmpty(), state.track?.artists.orEmpty()) },
                            onKaraoke = { karaoke = true },
                            translation = lyricsViewModel.translation.collectAsStateWithLifecycle().value,
                            onTranslate = lyricsViewModel::toggleTranslate,
                            onDownload = { loaded -> saveLyrics(loaded, state.track?.name.orEmpty(), state.track?.artists.orEmpty()) },
                        )
                    },
                    artworkModifier = artwork,
                    titleModifier = title,
                    lyricPeek = {
                        val lyrics by lyricsViewModel.state.collectAsStateWithLifecycle()
                        com.spotineil.core.ui.LyricPeek(
                            (lyrics as? LyricsUiState.Loaded)?.lyrics,
                            state.positionMs,
                            onClick = { showLyrics = true },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    MiniPlayer(state, actions, miniPlayerModifier, artworkModifier = artwork, titleModifier = title)
                }
            }
        }
    }


    if (karaoke && expanded) {
        val lyrics by lyricsViewModel.state.collectAsStateWithLifecycle()
        // Stays open across songs (so vocals stay off); a song without lyrics just says so.
        val loaded = (lyrics as? LyricsUiState.Loaded)?.lyrics?.takeUnless { it.isInstrumental }
        run {
            KaraokeScreen(
                lyrics = loaded,
                lyricsLoading = lyrics is LyricsUiState.Loading || lyrics is LyricsUiState.Idle,
                positionMs = state.positionMs,
                durationMs = state.track?.durationMs ?: 0L,
                title = state.track?.name.orEmpty(),
                artist = state.track?.artists.orEmpty(),
                imageUrl = state.track?.imageUrl,
                isPlaying = state.playback.isPlaying,
                onTogglePlay = viewModel::togglePlayPause,
                onClose = { karaoke = false },
                voiceRemoval = viewModel.voiceRemoval.collectAsStateWithLifecycle().value,
                voiceModelDownloaded = { viewModel.voiceModelDownloaded },
                onKaraokeOpen = viewModel::setKaraokeOpen,
                onVocalsRemovedChange = viewModel::setVocalsRemoved,
            )
        }
    }

    if (carMode && expanded) {
        CarModeScreen(
            title = state.track?.name.orEmpty(),
            artist = state.track?.artists.orEmpty(),
            imageUrl = state.track?.imageUrl,
            isPlaying = state.playback.isPlaying,
            isLiked = state.isLiked.takeIf { state.canLike },
            onPrevious = viewModel::previous,
            onTogglePlay = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onLike = viewModel::toggleLike,
            onClose = { carMode = false },
        )
    }
    when (sheet) {
        "queue" -> QueueSheet(viewModel) { sheet = null }
        "devices" -> DevicesSheet { sheet = null }
        "playlist" -> state.track?.media?.let { media ->
            val context = androidx.compose.ui.platform.LocalContext.current
            com.spotineil.core.ui.AddToPlaylistSheet(
                listOf(media),
                onDismiss = { sheet = null },
                onMessage = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() },
            )
        }
        "sleep" -> SleepTimerSheet(state.sleepTimer, viewModel::setSleepTimer) { sheet = null }
    }
}
