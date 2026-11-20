package com.spotineil.feature.discover.free

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.ArchiveRepository
import com.spotineil.core.data.repository.CcMixterRepository
import com.spotineil.core.data.repository.FreeMusicRepository
import com.spotineil.core.data.repository.PodcastRepository
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.model.YouTubeVideo
import kotlinx.coroutines.flow.map
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.CollectionKind
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import com.spotineil.feature.discover.LoadState
import com.spotineil.feature.discover.R
import com.spotineil.feature.discover.StationRow
import com.spotineil.feature.discover.StreamTrackRow
import com.spotineil.feature.discover.searchFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FreeSearchResults(
    /** Main catalog (full songs in YouTube's player). */
    val youTube: List<YouTubeVideo> = emptyList(),
    /** Why YouTube results are missing (e.g. daily quota used up), when they are. */
    val youTubeError: Throwable? = null,
    val songs: List<StreamTrack> = emptyList(),
    val collections: List<ArchiveCollection> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
) {
    val isEmpty get() = youTube.isEmpty() && songs.isEmpty() && collections.isEmpty() && stations.isEmpty() && podcasts.isEmpty()
}

enum class SearchFilter(val label: Int) {
    All(R.string.free_filter_all),
    Songs(R.string.free_filter_songs),
    Albums(R.string.free_filter_albums),
    Radio(R.string.free_filter_stations),
    Podcasts(R.string.free_filter_podcasts),
}

@HiltViewModel
class FreeSearchViewModel @Inject constructor(
    private val music: FreeMusicRepository,
    private val ccMixter: CcMixterRepository,
    private val archive: ArchiveRepository,
    private val radio: RadioRepository,
    private val podcasts: PodcastRepository,
    private val youTube: YouTubeRepository,
    val freePlayer: FreePlayer,
) : ViewModel() {


    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val retry = MutableStateFlow(0)

    val favorites = radio.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** null = nothing typed (show "Browse all"). */
    val results: StateFlow<LoadState<FreeSearchResults?>> = searchFlow(_query, retry) { q ->
        if (q.isEmpty()) null else searchAll(q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loaded(null))

    /** All sources in parallel; only fails if every one of them failed. */
    private suspend fun searchAll(q: String): FreeSearchResults = coroutineScope {
        val yt = async { runCatching { youTube.search(q) } }
        val songs = async { runCatching { music.search(q).let { it.audius + it.jamendo } } }
        val cc = async { runCatching { ccMixter.search(q) } }
        val collections = async { runCatching { archive.search(q) } }
        val stations = async { runCatching { radio.search(q, limit = 20) } }
        val shows = async { runCatching { podcasts.search(q) } }
        val all = listOf(yt.await(), songs.await(), cc.await(), collections.await(), stations.await(), shows.await())
        if (all.all { it.isFailure }) throw all.first().exceptionOrNull()!!
        FreeSearchResults(
            youTube = yt.await().getOrDefault(emptyList()),
            youTubeError = yt.await().exceptionOrNull(),
            songs = songs.await().getOrDefault(emptyList()) + cc.await().getOrDefault(emptyList()),
            collections = collections.await().getOrDefault(emptyList()),
            stations = stations.await().getOrDefault(emptyList()),
            podcasts = shows.await().getOrDefault(emptyList()),
        )
    }

    fun onQueryChange(q: String) { _query.value = q }
    fun retry() { retry.value++ }
    fun playTracks(list: List<StreamTrack>, index: Int) = freePlayer.playTracks(list, index)
    fun playStation(station: RadioStation) = freePlayer.playStation(station)
    fun toggleFavorite(station: RadioStation) = viewModelScope.launch {
        radio.setFavorite(station, favorite = favorites.value.none { it.id == station.id })
    }
    fun download(track: StreamTrack): Boolean = freePlayer.download(track)
    fun playYouTube(videos: List<YouTubeVideo>, index: Int) = freePlayer.playYouTube(videos, index)

    private companion object {
    }
}

/** "Browse all" tiles: special sections first, then music genres (Audius genre names + a radio tag). */
internal data class BrowseTile(val route: BrowseRoute, val color: Long)

internal val BROWSE_TILES = listOf(
    BrowseTile(BrowseRoute(BrowseKind.Podcasts.name, "Podcasts"), 0xFF006450),
    BrowseTile(BrowseRoute(BrowseKind.Concerts.name, "Live concerts"), 0xFF8400E7),
    BrowseTile(BrowseRoute(BrowseKind.Radio.name, "Radio"), 0xFF1E3264),
    BrowseTile(BrowseRoute(BrowseKind.Charts.name, "Charts"), 0xFF8D67AB),
    BrowseTile(BrowseRoute(BrowseKind.Albums.name, "Free albums"), 0xFFE8115B),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Pop", genre = "Pop", tag = "pop"), 0xFF148A08),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Hip-Hop", genre = "Hip-Hop/Rap", tag = "hiphop"), 0xFFBC5900),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Electronic", genre = "Electronic", tag = "electronic"), 0xFF0D73EC),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Lo-Fi", genre = "Lo-Fi", tag = "lofi"), 0xFF503750),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Rock", genre = "Rock", tag = "rock"), 0xFFE91429),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "R&B", genre = "R&B/Soul", tag = "rnb"), 0xFFDC148C),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Chill", genre = "Ambient", tag = "chillout"), 0xFF477D95),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Jazz", genre = "Jazz", tag = "jazz"), 0xFFA56752),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Classical", genre = "Classical", tag = "classical"), 0xFF7D4B32),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Latin", genre = "Latin", tag = "latin"), 0xFFE1118C),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "House", genre = "House", tag = "house"), 0xFF509BF5),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Alternative", genre = "Alternative", tag = "alternative"), 0xFF777777),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Country", genre = "Country", tag = "country"), 0xFFD84000),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Reggae", genre = "Reggae", tag = "reggae"), 0xFF608108),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Metal", genre = "Metal", tag = "metal"), 0xFF1E3264),
    BrowseTile(BrowseRoute(BrowseKind.Genre.name, "Soundtrack", genre = "Soundtrack", tag = "soundtrack"), 0xFF8C1932),
)

@Composable
fun FreeSearchRoute(nav: FreeNavigation, viewModel: FreeSearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    LaunchedEffect(query) { if (query.isBlank()) filter = SearchFilter.All }
    val context = LocalContext.current
    val favoriteIds = favorites.map { it.id }.toSet()
    val bottom = LocalBottomContentPadding.current + 16.dp

    val download: (StreamTrack) -> Unit = { t ->
        val ok = viewModel.download(t)
        nav.onMessage(context.getString(if (ok) R.string.discover_download_started else R.string.discover_download_failed))
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        item(span = { GridItemSpan(2) }, key = "title") {
            ScreenTitle(stringResource(R.string.free_search_title), nav.onSettings, Modifier.statusBarsPadding())
        }
        item(span = { GridItemSpan(2) }, key = "field") { SearchField(query, viewModel::onQueryChange) }

        when (val s = results) {
            LoadState.Loading -> items(8, span = { GridItemSpan(2) }) { SkeletonTrackRow() }
            is LoadState.Failed -> item(span = { GridItemSpan(2) }) { ErrorState(s.error, onRetry = viewModel::retry) }
            is LoadState.Loaded -> {
                val data = s.data
                if (data == null) {
                    item(span = { GridItemSpan(2) }, key = "browseH") {
                        Text(
                            stringResource(R.string.free_browse_all),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(BROWSE_TILES, key = { it.route.title }) { tile -> GenreTile(tile) { nav.onBrowse(tile.route) } }
                } else if (data.isEmpty) {
                    item(span = { GridItemSpan(2) }) { MessageState(Icons.Rounded.SearchOff, stringResource(R.string.free_no_results, query), null) }
                } else {
                    item(span = { GridItemSpan(2) }, key = "filters") {
                        FilterPills(SearchFilter.entries, filter, { stringResource(it.label) }, { filter = it }, Modifier.padding(vertical = 8.dp))
                    }
                    val show = { f: SearchFilter -> filter == SearchFilter.All || filter == f }
                    val limit = { n: Int -> if (filter == SearchFilter.All) n else Int.MAX_VALUE }
                    if (show(SearchFilter.Songs)) {
                        data.youTubeError?.takeIf { data.youTube.isEmpty() }?.let { err ->
                            item(span = { GridItemSpan(2) }, key = "ytErr") {
                                Text(
                                    com.spotineil.core.designsystem.component.errorMessage(err),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (data.youTube.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }, key = "ytH") {
                                Text(
                                    stringResource(R.string.yt_songs),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                        val videos = data.youTube.take(limit(6))
                        items(videos.size, span = { GridItemSpan(2) }, key = { "yt$it" }) { i ->
                            YouTubeRow(videos[i], isCurrent = false) { viewModel.playYouTube(data.youTube, i); nav.onYouTubePlayer() }
                        }
                    }
                    if (show(SearchFilter.Songs)) {
                        val songs = data.songs.take(limit(6))
                        items(songs.size, span = { GridItemSpan(2) }, key = { "song$it" }) { i ->
                            val t = songs[i]
                            StreamTrackRow(t, nowPlaying == t.toPlayable().mediaId, { viewModel.playTracks(data.songs, i) }, if (t.canDownload) ({ download(t) }) else null)
                        }
                    }
                    if (show(SearchFilter.Albums) && data.collections.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "albums") {
                            if (filter == SearchFilter.All) {
                                Shelf(stringResource(R.string.free_filter_albums), data.collections, key = { it.id }) { c ->
                                    BigCard(c.title, c.creator, c.artworkUrl, { nav.onCollection(c) }, placeholderIcon = Icons.Rounded.Album)
                                }
                            } else {
                                Column { data.collections.forEach { c -> CollectionRow(c) { nav.onCollection(c) } } }
                            }
                        }
                    }
                    if (show(SearchFilter.Radio)) {
                        val stations = data.stations.take(limit(4))
                        items(stations.size, span = { GridItemSpan(2) }, key = { "st$it" }) { i ->
                            val st = stations[i]
                            StationRow(st, st.id in favoriteIds, nowPlaying == st.toPlayable().mediaId, { viewModel.playStation(st) }, { viewModel.toggleFavorite(st) })
                        }
                    }
                    if (show(SearchFilter.Podcasts) && data.podcasts.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "podcasts") {
                            if (filter == SearchFilter.All) {
                                Shelf(stringResource(R.string.free_filter_podcasts), data.podcasts, key = { it.feedUrl }) { p ->
                                    BigCard(p.title, p.author, p.artworkUrl, { nav.onPodcast(p) }, placeholderIcon = Icons.Rounded.Podcasts)
                                }
                            } else {
                                Column {
                                    data.podcasts.forEach { p ->
                                        LibraryRow(p.title, stringResource(R.string.free_type_podcast, p.author), { nav.onPodcast(p) }) { m ->
                                            CoverImage(p.artworkUrl, null, m, placeholderIcon = Icons.Rounded.Podcasts)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.free_search_hint), color = Color(0xFF535353)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, null, tint = Color.Black) }
        },
        shape = RoundedCornerShape(6.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            cursorColor = Color.Black,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("free_search_field"),
    )
}

@Composable
private fun GenreTile(tile: BrowseTile, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(tile.color))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(tile.route.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Icon(
            when (BrowseKind.valueOf(tile.route.kind)) {
                BrowseKind.Podcasts -> Icons.Rounded.Podcasts
                BrowseKind.Radio -> Icons.Rounded.Radio
                else -> Icons.Rounded.Album
            },
            null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.align(Alignment.BottomEnd).size(40.dp),
        )
    }
}

@Composable
internal fun CollectionRow(c: ArchiveCollection, onClick: () -> Unit) {
    val subtitle = stringResource(
        if (c.kind == CollectionKind.Concert) R.string.free_type_concert else R.string.free_type_album,
        listOfNotNull(c.creator, c.date?.take(4)).joinToString(" • "),
    )
    LibraryRow(c.title, subtitle, onClick) { m -> CoverImage(c.artworkUrl, null, m, placeholderIcon = Icons.Rounded.Album) }
}
