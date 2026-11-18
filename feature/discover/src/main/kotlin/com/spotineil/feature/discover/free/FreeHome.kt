package com.spotineil.feature.discover.free

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.spotineil.core.data.repository.ArchiveRepository
import com.spotineil.core.data.repository.CcMixterRepository
import com.spotineil.core.data.repository.FreeMusicRepository
import com.spotineil.core.data.repository.LastFmRepository
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.PodcastRepository
import com.spotineil.core.data.repository.PreviewRepository
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.SkeletonCarousel
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.RecentItem
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A remote shelf: null while loading; failures become an empty shelf (hidden). */
data class HomeRemote(
    /** Main catalog: YouTube's most popular music videos in this country. */
    val youTube: List<YouTubeVideo>? = null,
    val trending: List<StreamTrack>? = null,
    val rising: List<StreamTrack>? = null,
    val jamendo: List<StreamTrack>? = null,
    val charts: List<StreamTrack>? = null,
    val lastFm: List<StreamTrack>? = null,
    val ccMixter: List<StreamTrack>? = null,
    val concerts: List<ArchiveCollection>? = null,
    val albums: List<ArchiveCollection>? = null,
    val stationsNear: List<RadioStation>? = null,
    val podcasts: List<Podcast>? = null,
    /** Set when every source failed (likely offline). */
    val error: Throwable? = null,
) {
    val isLoading: Boolean get() = youTube == null && trending == null && charts == null && concerts == null && stationsNear == null && podcasts == null
}

data class FreeHomeUiState(
    val remote: HomeRemote = HomeRemote(),
    val recents: List<RecentItem> = emptyList(),
    val likedCount: Int = 0,
    val favoriteStations: List<RadioStation> = emptyList(),
    val followedPodcasts: List<Podcast> = emptyList(),
    val nowPlayingId: String? = null,
    val isRefreshing: Boolean = false,
)

enum class HomeFilter(val label: Int) {
    All(R.string.free_filter_all),
    Music(R.string.free_filter_music),
    Radio(R.string.free_filter_radio),
    Podcasts(R.string.free_filter_podcasts),
}

@HiltViewModel
class FreeHomeViewModel @Inject constructor(
    private val music: FreeMusicRepository,
    private val previews: PreviewRepository,
    private val lastFm: LastFmRepository,
    private val ccMixter: CcMixterRepository,
    private val archive: ArchiveRepository,
    private val radio: RadioRepository,
    private val podcasts: PodcastRepository,
    private val youTube: YouTubeRepository,
    recents: RecentsRepository,
    liked: LikedMediaRepository,
    val freePlayer: FreePlayer,
) : ViewModel() {

    val countryCode: String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    val countryName: String = Locale("", countryCode).displayCountry

    private val remote = MutableStateFlow(HomeRemote())
    private val refreshing = MutableStateFlow(false)

    /**
     * The quick tiles show a snapshot of recent items, refreshed when Home is shown again or
     * pulled to refresh — never while you're looking at it, so tiles don't jump under your finger
     * the moment something starts playing.
     */
    private val liveRecents = recents.recent(12).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val tileRecents = MutableStateFlow<List<RecentItem>>(emptyList())

    fun onScreenShown() { tileRecents.value = liveRecents.value }

    val state: StateFlow<FreeHomeUiState> = combine(
        remote,
        tileRecents,
        combine(liked.liked, radio.favorites, podcasts.followed) { l, s, p -> Triple(l.size, s, p) },
        freePlayer.nowPlayingId,
        refreshing,
    ) { r, rec, (likedCount, stations, followed), playing, isRefreshing ->
        FreeHomeUiState(r, rec, likedCount, stations, followed, playing, isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FreeHomeUiState())

    init {
        load()
        // First history (or a fresh install) fills the tiles right away.
        viewModelScope.launch { liveRecents.collect { if (tileRecents.value.isEmpty()) tileRecents.value = it } }
    }

    fun refresh() {
        if (refreshing.value) return
        onScreenShown()
        refreshing.value = true
        load(onDone = { refreshing.value = false })
    }

    /** Every shelf loads on its own, so one slow or broken source never blocks the others. */
    private fun load(onDone: () -> Unit = {}) = viewModelScope.launch {
        val failures = mutableListOf<Throwable>()
        suspend fun <T> shelf(block: suspend () -> List<T>, set: HomeRemote.(List<T>) -> HomeRemote) {
            // Network hiccups (e.g. right after the phone wakes up) are retried a couple of times
            // before a shelf is given up on.
            var result = runCatching { block() }
            for (wait in RETRY_DELAYS_MS) {
                val error = result.exceptionOrNull()
                if (error !is SpotineilException.Network && error !is SpotineilException.Server) break
                kotlinx.coroutines.delay(wait)
                result = runCatching { block() }
            }
            result.exceptionOrNull()?.let { synchronized(failures) { failures += it } }
            remote.update { it.set(result.getOrDefault(emptyList())) }
        }
        val jobs = listOf(
            launch { shelf({ youTube.trending() }) { copy(youTube = it) } },
            launch { shelf({ music.trending() }) { copy(trending = it) } },
            launch { shelf({ music.underground() }) { copy(rising = it) } },
            launch { shelf({ music.jamendoPopular() }) { copy(jamendo = it) } },
            launch { shelf({ previews.charts() }) { copy(charts = it) } },
            launch { shelf({ if (lastFm.isEnabled) lastFm.topTracks() else emptyList() }) { copy(lastFm = it) } },
            launch { shelf({ ccMixter.featured() }) { copy(ccMixter = it) } },
            launch { shelf({ archive.concerts() }) { copy(concerts = it) } },
            launch { shelf({ archive.albums() }) { copy(albums = it) } },
            launch { shelf({ radio.byCountry(countryCode, limit = 20).ifEmpty { radio.top(20) } }) { copy(stationsNear = it) } },
            launch { shelf({ podcasts.top() }) { copy(podcasts = it) } },
        )
        jobs.forEach { it.join() }
        val allFailed = failures.size >= jobs.size - 3 // Last.fm/Jamendo/YouTube may be disabled (never fail).
        remote.update { it.copy(error = if (allFailed) failures.firstOrNull() ?: SpotineilException.Unknown() else null) }
        onDone()
    }

    fun retry() {
        remote.value = HomeRemote()
        load()
    }

    private companion object {
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L)
    }

    /** True when it's a YouTube song (the caller opens YouTube's player). */
    fun playRecent(item: RecentItem.Song): Boolean = freePlayer.playMixed(listOf(item.media), 0)
    fun playYouTube(videos: List<YouTubeVideo>, index: Int) = freePlayer.playYouTube(videos, index)
    fun playStation(station: RadioStation) = freePlayer.playStation(station)
    fun playTracks(tracks: List<StreamTrack>, index: Int) = freePlayer.playTracks(tracks, index)
}

/** Callbacks from the free screens into app navigation. */
data class FreeNavigation(
    val onPodcast: (Podcast) -> Unit,
    val onCollection: (ArchiveCollection) -> Unit,
    val onBrowse: (BrowseRoute) -> Unit,
    val onLikedSongs: () -> Unit,
    /** Opens YouTube's player (after [FreePlayer.playYouTube] set the queue). */
    val onYouTubePlayer: () -> Unit,
    val onLocalMusic: () -> Unit,
    val onSettings: () -> Unit,
    val onBack: () -> Unit,
    val onMessage: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeHomeRoute(nav: FreeNavigation, viewModel: FreeHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.onScreenShown()
        onPauseOrDispose { }
    }
    val r = state.remote
    val music = filter == HomeFilter.All || filter == HomeFilter.Music
    val radioOn = filter == HomeFilter.All || filter == HomeFilter.Radio
    val podcastsOn = filter == HomeFilter.All || filter == HomeFilter.Podcasts
    val previewLabel = stringResource(R.string.free_preview)
    val liveLabel = stringResource(R.string.free_live)

    val trackShelf: @Composable (Int, List<StreamTrack>?) -> Unit = { title, tracks ->
        Shelf(stringResource(title), tracks.orEmpty(), key = { it.source.name + it.id }) { t ->
            val list = tracks.orEmpty()
            BigCard(
                title = t.title,
                subtitle = t.artist,
                imageUrl = t.artworkUrl,
                onClick = { viewModel.playTracks(list, list.indexOf(t)) },
                badge = if (t.isPreview) previewLabel else null,
                isPlaying = state.nowPlayingId == t.toPlayable().mediaId,
            )
        }
    }
    val collectionShelf: @Composable (Int, List<ArchiveCollection>?) -> Unit = { title, items ->
        Shelf(stringResource(title), items.orEmpty(), key = { it.id }) { c ->
            BigCard(c.title, listOfNotNull(c.creator, c.date?.take(4)).joinToString(" • "), c.artworkUrl, { nav.onCollection(c) }, placeholderIcon = Icons.Rounded.Album)
        }
    }
    val stationShelf: @Composable (String, List<RadioStation>) -> Unit = { title, items ->
        Shelf(title, items, key = { it.id }) { s ->
            BigCard(
                s.name, s.subtitle, s.faviconUrl, { viewModel.playStation(s) },
                badge = liveLabel, isPlaying = state.nowPlayingId == s.toPlayable().mediaId, placeholderIcon = Icons.Rounded.Radio,
            )
        }
    }
    val podcastShelf: @Composable (Int, List<Podcast>?) -> Unit = { title, items ->
        Shelf(stringResource(title), items.orEmpty(), key = { it.feedUrl }) { p ->
            BigCard(p.title, p.author, p.artworkUrl, { nav.onPodcast(p) }, placeholderIcon = Icons.Rounded.Podcasts)
        }
    }

    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
            item(key = "chips") {
                FilterPills(
                    options = HomeFilter.entries,
                    selected = filter,
                    label = { stringResource(it.label) },
                    onSelect = { filter = it },
                    modifier = Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 8.dp),
                    leading = { ProfileButton(nav.onSettings) },
                )
            }
            item(key = "tiles") { HomeTiles(state, filter, viewModel, nav) }

            if (r.error != null && r.isLoading.not() && state.recents.isEmpty()) {
                item(key = "error") { ErrorState(r.error, onRetry = viewModel::retry) }
            }
            if (r.isLoading) {
                items(3) { SkeletonCarousel(Modifier.padding(top = 24.dp)) }
            }

            if (music) {
                item(key = "youtube") {
                    val videos = r.youTube.orEmpty()
                    Shelf(stringResource(R.string.yt_trending), videos, key = { "yt:" + it.id }) { v ->
                        BigCard(
                            title = v.songTitle,
                            subtitle = v.artist,
                            imageUrl = v.thumbnailUrl,
                            onClick = { viewModel.playYouTube(videos, videos.indexOf(v)); nav.onYouTubePlayer() },
                            badge = "YouTube",
                        )
                    }
                }
            }
            if (music) {
                item(key = "trending") { trackShelf(R.string.free_trending, r.trending) }
                item(key = "charts") { trackShelf(R.string.free_charts, r.charts) }
            }
            if (radioOn) {
                item(key = "yourStations") { stationShelf(stringResource(R.string.free_your_stations), state.favoriteStations) }
                item(key = "near") { stationShelf(stringResource(R.string.free_stations_near, viewModel.countryName), r.stationsNear.orEmpty()) }
            }
            if (podcastsOn) {
                item(key = "yourPodcasts") { podcastShelf(R.string.free_your_podcasts, state.followedPodcasts) }
                item(key = "topPodcasts") { podcastShelf(R.string.free_top_podcasts, r.podcasts) }
            }
            if (music) {
                item(key = "concerts") { collectionShelf(R.string.free_concerts, r.concerts) }
                item(key = "rising") { trackShelf(R.string.free_rising, r.rising) }
                item(key = "jamendo") { trackShelf(R.string.free_jamendo, r.jamendo) }
                item(key = "lastfm") { trackShelf(R.string.free_lastfm, r.lastFm) }
                item(key = "albums") { collectionShelf(R.string.free_albums, r.albums) }
                item(key = "cc") { trackShelf(R.string.free_ccmixter, r.ccMixter) }
            }
        }
    }
}

/** Liked Songs + the most recent things you played, as 2-column tiles. */
@Composable
private fun HomeTiles(state: FreeHomeUiState, filter: HomeFilter, viewModel: FreeHomeViewModel, nav: FreeNavigation) {
    val recents = state.recents.filter {
        when (filter) {
            HomeFilter.All -> true
            HomeFilter.Music -> it is RecentItem.Song || it is RecentItem.Collection
            HomeFilter.Radio -> it is RecentItem.Station
            HomeFilter.Podcasts -> it is RecentItem.Show || (it is RecentItem.Song && it.media.isEpisode)
        }
    }
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (filter == HomeFilter.All || filter == HomeFilter.Music) {
            add { m -> QuickTile(stringResource(R.string.free_liked_songs), nav.onLikedSongs, m) { LikedArt() } }
        }
        recents.take(if (isEmpty()) 8 else 7).forEach { item ->
            add { m ->
                QuickTile(
                    item.title,
                    onClick = {
                        when (item) {
                            is RecentItem.Song -> if (viewModel.playRecent(item)) nav.onYouTubePlayer()
                            is RecentItem.Station -> viewModel.playStation(item.station)
                            is RecentItem.Show -> nav.onPodcast(item.podcast)
                            is RecentItem.Collection -> nav.onCollection(item.collection)
                        }
                    },
                    modifier = m,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        CoverImage(
                            item.imageUrl, null, Modifier.fillMaxSize(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
                            placeholderIcon = when (item) {
                                is RecentItem.Station -> Icons.Rounded.Radio
                                is RecentItem.Show -> Icons.Rounded.Podcasts
                                is RecentItem.Collection -> Icons.Rounded.Album
                                is RecentItem.Song -> androidx.compose.material.icons.Icons.Rounded.Album
                            },
                        )
                    }
                }
            }
        }
    }
    if (tiles.isNotEmpty()) QuickTileGrid(tiles)
}
