package com.spotineil.feature.discover.free

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.spotineil.core.data.repository.ArchiveRepository
import com.spotineil.core.data.repository.FreeMusicRepository
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.PodcastRepository
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.model.isYouTube
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.ArchiveCollectionDetail
import com.spotineil.core.model.CollectionKind
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.PodcastDetail
import com.spotineil.core.model.PodcastEpisode
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import com.spotineil.feature.discover.LoadState
import com.spotineil.feature.discover.R
import com.spotineil.feature.discover.StationRow
import com.spotineil.feature.discover.StreamTrackRow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Shared header for detail pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetailHeader(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    placeholderIcon: ImageVector,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val dominant by rememberDominantColor(imageUrl, MaterialTheme.colorScheme.surfaceContainerHigh)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(dominant.copy(alpha = 0.9f), background)))
            .statusBarsPadding(),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
        CoverImage(
            imageUrl,
            title,
            Modifier.align(Alignment.CenterHorizontally).size(220.dp),
            shape = RoundedCornerShape(4.dp),
            placeholderIcon = placeholderIcon,
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
private fun PlayShuffle(onPlay: () -> Unit, onShuffle: () -> Unit) {
    IconButton(onClick = onShuffle) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.free_shuffle), Modifier.size(28.dp)) }
    FilledIconButton(
        onClick = onPlay,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.free_play), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun SourceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Podcast
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class PodcastViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val podcast: Podcast = savedStateHandle.toRoute<PodcastRoute>().toPodcast()

    private val _state = MutableStateFlow<LoadState<PodcastDetail>>(LoadState.Loading)
    val state: StateFlow<LoadState<PodcastDetail>> = _state.asStateFlow()

    val isFollowed = repository.isFollowed(podcast.feedUrl).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
        freePlayer.rememberPodcast(podcast)
    }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = runCatching { repository.detail(podcast) }.fold({ LoadState.Loaded(it) }, { LoadState.Failed(it) })
    }

    fun toggleFollow() = viewModelScope.launch {
        val show = (state.value as? LoadState.Loaded)?.data?.podcast ?: podcast
        repository.setFollowed(show, !isFollowed.value)
    }

    fun play(detail: PodcastDetail, index: Int) =
        freePlayer.playMedia(detail.episodes.map { it.toPlayable(detail.podcast) }, index)
}

@Composable
fun PodcastScreen(nav: FreeNavigation, viewModel: PodcastViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followed by viewModel.isFollowed.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val detail = (state as? LoadState.Loaded)?.data
    val podcast = detail?.podcast ?: viewModel.podcast

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        item(key = "header") {
            DetailHeader(podcast.artworkUrl, podcast.title, podcast.author, nav.onBack, Icons.Rounded.Podcasts) {
                OutlinedButton(onClick = viewModel::toggleFollow, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(if (followed) R.string.free_following else R.string.free_follow))
                }
                Spacer(Modifier.weight(1f))
                if (detail != null && detail.episodes.isNotEmpty()) {
                    FilledIconButton(
                        onClick = { viewModel.play(detail, 0) },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.free_play), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        when (val s = state) {
            LoadState.Loading -> items(6) { SkeletonTrackRow() }
            is LoadState.Failed -> item { ErrorState(s.error, onRetry = { viewModel.load() }) }
            is LoadState.Loaded -> {
                s.data.description?.takeIf { it.isNotBlank() }?.let { text ->
                    item(key = "desc") { ExpandableText(text) }
                }
                item(key = "epH") {
                    Text(
                        stringResource(R.string.free_episodes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(s.data.episodes, key = { _, e -> e.id }) { i, e ->
                    EpisodeRow(e, nowPlaying == "episode:${e.id}") { viewModel.play(s.data, i) }
                }
                item(key = "note") { SourceNote(stringResource(R.string.free_source_podcast)) }
            }
        }
    }
}

@Composable
private fun ExpandableText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, isCurrent: Boolean, onClick: () -> Unit) {
    val date = episode.publishedAtMs?.let { remember(it) { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it)) } }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(date, episode.durationMs.takeIf { it > 0 }?.let { formatDuration(it) }).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(
            Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isCurrent) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                stringResource(R.string.free_play),
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Internet Archive album / concert
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ArchiveRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val collection: ArchiveCollection = savedStateHandle.toRoute<CollectionRoute>().toCollection()

    private val _state = MutableStateFlow<LoadState<ArchiveCollectionDetail>>(LoadState.Loading)
    val state: StateFlow<LoadState<ArchiveCollectionDetail>> = _state.asStateFlow()
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
        freePlayer.rememberCollection(collection)
    }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = runCatching { repository.detail(collection) }.fold({ LoadState.Loaded(it) }, { LoadState.Failed(it) })
    }

    fun play(tracks: List<StreamTrack>, index: Int) = freePlayer.playTracks(tracks, index)
    fun shuffle(tracks: List<StreamTrack>) = freePlayer.playTracks(tracks.shuffled(), 0)
    fun download(track: StreamTrack) = freePlayer.download(track)
}

@Composable
fun CollectionScreen(nav: FreeNavigation, viewModel: CollectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val detail = (state as? LoadState.Loaded)?.data
    val c = detail?.collection ?: viewModel.collection
    val subtitle = stringResource(
        if (c.kind == CollectionKind.Concert) R.string.free_type_concert else R.string.free_type_album,
        listOfNotNull(c.creator, c.date).joinToString(" • "),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        item(key = "header") {
            DetailHeader(c.artworkUrl, c.title, subtitle, nav.onBack, Icons.Rounded.Album) {
                Spacer(Modifier.weight(1f))
                val tracks = detail?.tracks.orEmpty()
                if (tracks.isNotEmpty()) PlayShuffle(onPlay = { viewModel.play(tracks, 0) }, onShuffle = { viewModel.shuffle(tracks) })
            }
        }
        when (val s = state) {
            LoadState.Loading -> items(8) { SkeletonTrackRow() }
            is LoadState.Failed -> item { ErrorState(s.error, onRetry = { viewModel.load() }) }
            is LoadState.Loaded -> {
                val tracks = s.data.tracks
                if (tracks.isEmpty()) item { MessageState(Icons.Rounded.MusicOff, stringResource(R.string.free_no_tracks), null) }
                itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                    StreamTrackRow(t, nowPlaying == t.toPlayable().mediaId, { viewModel.play(tracks, i) }, if (t.canDownload) ({
                        val ok = viewModel.download(t)
                        nav.onMessage(context.getString(if (ok) R.string.discover_download_started else R.string.discover_download_failed))
                    }) else null)
                }
                s.data.description?.let { item(key = "desc") { ExpandableText(it) } }
                item(key = "note") { SourceNote(stringResource(R.string.free_source_archive)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Browse (from the Search grid): a genre, or a whole category
// ---------------------------------------------------------------------------------------------

enum class BrowseKind { Genre, Concerts, Albums, Radio, Podcasts, Charts }

data class BrowseContent(
    val videos: List<YouTubeVideo> = emptyList(),
    val tracks: List<StreamTrack> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
    val collections: List<ArchiveCollection> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val music: FreeMusicRepository,
    private val radio: RadioRepository,
    private val archive: ArchiveRepository,
    private val podcasts: PodcastRepository,
    private val youTube: YouTubeRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val route: BrowseRoute = savedStateHandle.toRoute<BrowseRoute>()
    val kind: BrowseKind = runCatching { BrowseKind.valueOf(route.kind) }.getOrDefault(BrowseKind.Genre)

    private val _state = MutableStateFlow<LoadState<BrowseContent>>(LoadState.Loading)
    val state: StateFlow<LoadState<BrowseContent>> = _state.asStateFlow()
    val favorites = radio.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = runCatching { fetch() }.fold({ LoadState.Loaded(it) }, { LoadState.Failed(it) })
    }

    private suspend fun fetch(): BrowseContent = coroutineScope {
        when (kind) {
            BrowseKind.Genre -> {
                val videos = async { runCatching { youTube.search("${route.title} music") } }
                val tracks = async { runCatching { music.trending(genre = route.genre, limit = 40) } }
                val stations = async { runCatching { route.tag?.let { radio.byTag(it, limit = 25) }.orEmpty() } }
                val v = videos.await()
                val t = tracks.await()
                val s = stations.await()
                if (v.isFailure && t.isFailure && s.isFailure) throw t.exceptionOrNull()!!
                BrowseContent(videos = v.getOrDefault(emptyList()), tracks = t.getOrDefault(emptyList()), stations = s.getOrDefault(emptyList()))
            }
            BrowseKind.Concerts -> BrowseContent(collections = archive.concerts(page = 1) + runCatching { archive.concerts(page = 2) }.getOrDefault(emptyList()))
            BrowseKind.Albums -> BrowseContent(collections = archive.albums(page = 1) + runCatching { archive.albums(page = 2) }.getOrDefault(emptyList()))
            BrowseKind.Radio -> {
                val near = async { runCatching { radio.byCountry(country, limit = 30) }.getOrDefault(emptyList()) }
                val top = async { radio.top(50) }
                BrowseContent(stations = (near.await() + top.await()).distinctBy { it.id })
            }
            BrowseKind.Podcasts -> BrowseContent(podcasts = podcasts.top(50))
            BrowseKind.Charts -> BrowseContent(videos = youTube.trending())
        }
    }

    fun playTracks(list: List<StreamTrack>, index: Int) = freePlayer.playTracks(list, index)
    fun playStation(station: RadioStation) = freePlayer.playStation(station)
    fun toggleFavorite(station: RadioStation) = viewModelScope.launch {
        radio.setFavorite(station, favorite = favorites.value.none { it.id == station.id })
    }
    fun download(track: StreamTrack) = freePlayer.download(track)
    fun playYouTube(videos: List<YouTubeVideo>, index: Int) = freePlayer.playYouTube(videos, index)
}

@Composable
fun BrowseScreen(nav: FreeNavigation, viewModel: BrowseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val favoriteIds = favorites.map { it.id }.toSet()
    val context = LocalContext.current
    val title = viewModel.route.title

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "top") {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        when (val s = state) {
            LoadState.Loading -> items(8, span = { GridItemSpan(maxLineSpan) }) { SkeletonTrackRow() }
            is LoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(s.error, onRetry = { viewModel.load() }) }
            is LoadState.Loaded -> {
                val d = s.data
                if (d.videos.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "vH") { GridHeader(stringResource(R.string.yt_genre, title)) }
                }
                items(d.videos.size, span = { GridItemSpan(maxLineSpan) }, key = { "v$it" }) { i ->
                    YouTubeRow(d.videos[i], isCurrent = false) { viewModel.playYouTube(d.videos, i); nav.onYouTubePlayer() }
                }
                if (d.tracks.isNotEmpty() && viewModel.kind == BrowseKind.Genre) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "tH") { GridHeader(stringResource(R.string.free_genre_tracks, title)) }
                }
                items(d.tracks.size, span = { GridItemSpan(maxLineSpan) }, key = { "t$it" }) { i ->
                    val t = d.tracks[i]
                    StreamTrackRow(t, nowPlaying == t.toPlayable().mediaId, { viewModel.playTracks(d.tracks, i) }, if (t.canDownload) ({
                        val ok = viewModel.download(t)
                        nav.onMessage(context.getString(if (ok) R.string.discover_download_started else R.string.discover_download_failed))
                    }) else null)
                }
                if (d.stations.isNotEmpty() && viewModel.kind == BrowseKind.Genre) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sH") { GridHeader(stringResource(R.string.free_genre_stations, title)) }
                }
                items(d.stations.size, span = { GridItemSpan(maxLineSpan) }, key = { "s$it" }) { i ->
                    val st = d.stations[i]
                    StationRow(st, st.id in favoriteIds, nowPlaying == st.toPlayable().mediaId, { viewModel.playStation(st) }, { viewModel.toggleFavorite(st) })
                }
                items(d.collections, key = { "c:" + it.id }) { c ->
                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                        BigCard(c.title, listOfNotNull(c.creator, c.date?.take(4)).joinToString(" • "), c.artworkUrl, { nav.onCollection(c) }, placeholderIcon = Icons.Rounded.Album)
                    }
                }
                items(d.podcasts, key = { "p:" + it.feedUrl }) { p ->
                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                        BigCard(p.title, p.author, p.artworkUrl, { nav.onPodcast(p) }, placeholderIcon = Icons.Rounded.Podcasts)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
}

// ---------------------------------------------------------------------------------------------
// Liked Songs (on-device)
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class LikedMediaViewModel @Inject constructor(
    private val repository: LikedMediaRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val liked: StateFlow<List<PlayableMedia>?> = repository.liked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when the tapped song is a YouTube one (open YouTube's player). */
    fun play(list: List<PlayableMedia>, index: Int): Boolean = freePlayer.playMixed(list, index)
    fun shuffle(list: List<PlayableMedia>): Boolean {
        val onDevice = list.filterNot { it.isYouTube }
        return if (onDevice.isNotEmpty()) { freePlayer.playMedia(onDevice.shuffled(), 0); false } else freePlayer.playMixed(list.shuffled(), 0)
    }
    fun unlike(media: PlayableMedia) = viewModelScope.launch { repository.setLiked(media, false) }
}

@Composable
fun LikedMediaScreen(nav: FreeNavigation, viewModel: LikedMediaViewModel = hiltViewModel()) {
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val list = liked.orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF5038A0), MaterialTheme.colorScheme.background)))
                    .statusBarsPadding(),
            ) {
                IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
                LikedArt(Modifier.align(Alignment.CenterHorizontally).size(180.dp).clip(RoundedCornerShape(4.dp)), iconSize = 64.dp)
                Text(
                    stringResource(R.string.free_liked_songs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        androidx.compose.ui.res.pluralStringResource(R.plurals.free_liked_count, list.size, list.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    if (list.isNotEmpty()) PlayShuffle(
                        onPlay = { if (viewModel.play(list, 0)) nav.onYouTubePlayer() },
                        onShuffle = { if (viewModel.shuffle(list)) nav.onYouTubePlayer() },
                    )
                }
            }
        }
        if (liked != null && list.isEmpty()) {
            item { Text(stringResource(R.string.free_liked_empty), Modifier.padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(list, key = { _, m -> m.mediaId }) { i, m ->
            LikedRow(m, nowPlaying == m.mediaId, onClick = { if (viewModel.play(list, i)) nav.onYouTubePlayer() }, onUnlike = { viewModel.unlike(m) })
        }
    }
}

@Composable
private fun LikedRow(media: PlayableMedia, isCurrent: Boolean, onClick: () -> Unit, onUnlike: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(media.artworkUrl, null, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                media.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(media.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onUnlike) {
            Icon(Icons.Rounded.Favorite, stringResource(R.string.free_liked_songs), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
