package com.spotineil.feature.discover.free

import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
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
import com.spotineil.core.data.db.RecentSearchDao
import com.spotineil.core.data.db.RecentSearchEntity
import androidx.compose.material.icons.rounded.History
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val recentDao: RecentSearchDao,
    val freePlayer: FreePlayer,
) : ViewModel() {


    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val favorites = radio.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What the last search found; null = nothing searched (show "Browse all"). It only changes
     * when the user presses the keyboard's search button, so coming back from a song shows the
     * same results instead of searching again.
     */
    private val _results = MutableStateFlow<LoadState<FreeSearchResults?>>(LoadState.Loaded(null))
    val results: StateFlow<LoadState<FreeSearchResults?>> = _results.asStateFlow()
    private var searchJob: Job? = null

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

    /** The text of the search currently shown (or being fetched). */
    private var searchedQuery: String? = null

    /**
     * Searches as you type, shortly after the last key. Only an actual change of the text
     * searches: coming back from a song, or tapping the box without editing, keeps the results.
     */
    fun onQueryChange(q: String) {
        _query.value = q
        val text = q.trim()
        if (text.isEmpty()) {
            searchJob?.cancel()
            searchedQuery = null
            _results.value = LoadState.Loaded(null)
            return
        }
        if (text == searchedQuery) return
        search(text, debounce = true)
    }

    /** The keyboard's search button: search now (nothing new happens if these results are already shown). */
    fun submit() {
        val text = _query.value.trim()
        if (text.isEmpty()) {
            onQueryChange("")
            return
        }
        if (text == searchedQuery && _results.value !is LoadState.Failed) { rememberQuery(); return }
        search(text, debounce = false)
        rememberQuery()
    }

    /** "Try again" after a failed search. */
    fun retry() {
        val text = _query.value.trim()
        if (text.isNotEmpty()) search(text, debounce = false)
    }

    private fun search(text: String, debounce: Boolean) {
        searchJob?.cancel()
        searchedQuery = text
        searchJob = viewModelScope.launch {
            if (debounce) delay(TYPING_PAUSE_MS)
            _results.value = LoadState.Loading
            _results.value = try {
                LoadState.Loaded(searchAll(text))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Failed(e)
            }
        }
    }

    /** Searches that were run, newest first, so they need not be typed again. */
    val recentSearches: StateFlow<List<String>> = recentDao.observe(RECENT_LIMIT)
        .map { list -> list.map { it.query } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keeps the search being shown in the history (after Search was pressed or a result was opened). */
    fun rememberQuery() {
        val text = searchedQuery ?: return
        viewModelScope.launch {
            recentDao.upsert(RecentSearchEntity(text, System.currentTimeMillis()))
            recentDao.trim(RECENT_LIMIT)
        }
    }

    /** A recent search was tapped: put it in the box and search now. */
    fun searchRecent(text: String) {
        _query.value = text
        search(text, debounce = false)
        rememberQuery()
    }

    fun removeRecent(text: String) { viewModelScope.launch { recentDao.delete(text) } }
    fun clearRecents() { viewModelScope.launch { recentDao.clear() } }

    fun playTracks(list: List<StreamTrack>, index: Int) { rememberQuery(); freePlayer.playTracks(list, index) }
    fun playStation(station: RadioStation) { rememberQuery(); freePlayer.playStation(station) }
    fun toggleFavorite(station: RadioStation) = viewModelScope.launch {
        radio.setFavorite(station, favorite = favorites.value.none { it.id == station.id })
    }
    fun download(track: StreamTrack): Boolean = freePlayer.download(track)
    fun playYouTube(videos: List<YouTubeVideo>, index: Int) { rememberQuery(); freePlayer.playYouTube(videos, index) }

    private companion object {
        /** Wait for a pause in typing before searching. */
        const val TYPING_PAUSE_MS = 450L
        const val RECENT_LIMIT = 12
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
    val recents by viewModel.recentSearches.collectAsStateWithLifecycle()
    val moodsViewModel: MoodsViewModel = hiltViewModel()
    val moods by moodsViewModel.moods.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    LaunchedEffect(query) { if (query.isBlank()) filter = SearchFilter.All }
    val context = LocalContext.current
    val favoriteIds = favorites.map { it.id }.toSet()
    val bottom = LocalBottomContentPadding.current + 16.dp

    val download: (StreamTrack) -> Unit = { t ->
        val ok = viewModel.download(t)
        nav.onMessage(context.getString(if (ok) R.string.discover_download_started else R.string.discover_download_failed))
    }

    Column(Modifier.fillMaxSize()) {
    // Title and search box stay put; only the results scroll under them.
    ScreenTitle(stringResource(R.string.free_search_title), nav.onSettings, Modifier.statusBarsPadding())
    // Voice search: Android's speech recogniser fills in the box and searches.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(viewModel::searchRecent)
    }
    SearchField(query, viewModel::onQueryChange, onSearch = viewModel::submit, onVoice = {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.free_search_hint))
        try { voice.launch(intent) } catch (_: android.content.ActivityNotFoundException) { nav.onMessage(context.getString(R.string.free_voice_unavailable)) }
    })
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(bottom = bottom),
    ) {

        when (val s = results) {
            LoadState.Loading -> items(8, span = { GridItemSpan(2) }) { SkeletonTrackRow() }
            is LoadState.Failed -> item(span = { GridItemSpan(2) }) { ErrorState(s.error, onRetry = viewModel::retry) }
            is LoadState.Loaded -> {
                val data = s.data
                if (data == null) {
                    if (recents.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "recentH") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.free_recent_searches),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::clearRecents) { Text(stringResource(R.string.free_recent_clear)) }
                            }
                        }
                        items(recents.size, span = { GridItemSpan(2) }, key = { "recent_${recents[it]}" }) { i ->
                            val text = recents[i]
                            Row(
                                Modifier.fillMaxWidth().clickable { viewModel.searchRecent(text) }.padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { viewModel.removeRecent(text) }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.free_recent_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(2) }, key = "browseH") {
                        Text(
                            stringResource(R.string.free_browse_all),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(BROWSE_TILES, key = { it.route.title }) { tile -> GenreTile(tile) { nav.onBrowse(tile.route) } }
                    if (moods.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "moodsH") {
                            Text(
                                stringResource(R.string.discovery_moods),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        items(moods.size, key = { "mood_" + moods[it].title }) { i ->
                            val mood = moods[i]
                            Box(
                                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp).fillMaxWidth().height(64.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(moodColor(mood, i))
                                    .clickable { nav.onMood(mood) }.padding(12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(mood.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                            }
                        }
                    }
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
                                    BigCard(c.title, c.creator, c.artworkUrl, { viewModel.rememberQuery(); nav.onCollection(c) }, placeholderIcon = Icons.Rounded.Album)
                                }
                            } else {
                                Column { data.collections.forEach { c -> CollectionRow(c) { viewModel.rememberQuery(); nav.onCollection(c) } } }
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
                                    BigCard(p.title, p.author, p.artworkUrl, { viewModel.rememberQuery(); nav.onPodcast(p) }, placeholderIcon = Icons.Rounded.Podcasts)
                                }
                            } else {
                                Column {
                                    data.podcasts.forEach { p ->
                                        LibraryRow(p.title, stringResource(R.string.free_type_podcast, p.author), { viewModel.rememberQuery(); nav.onPodcast(p) }) { m ->
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
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, onVoice: (() -> Unit)? = null) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
            onSearch()
            focusManager.clearFocus()
        }),
        placeholder = { Text(stringResource(R.string.free_search_hint), color = Color(0xFF535353)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, null, tint = Color.Black) }
            } else if (onVoice != null) {
                IconButton(onClick = onVoice) { Icon(Icons.Rounded.Mic, stringResource(R.string.free_voice_search), tint = Color.Black) }
            }
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
