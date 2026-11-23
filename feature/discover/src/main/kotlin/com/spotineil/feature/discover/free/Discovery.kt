package com.spotineil.feature.discover.free

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.spotineil.core.data.innertube.InnerTubeCatalogRepository
import com.spotineil.core.data.innertube.YtArtistPage
import com.spotineil.core.data.innertube.YtMood
import com.spotineil.core.data.repository.ArtistStat
import com.spotineil.core.data.repository.LastFmRepository
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.ListeningSummary
import com.spotineil.core.data.repository.PlayLogRepository
import com.spotineil.core.data.repository.SongStat
import com.spotineil.core.data.repository.YouTubeRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.model.toYouTubeVideo
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class YtArtistRoute(val name: String, val channelId: String? = null, val imageUrl: String? = null)
@Serializable data class YtCollectionRoute(val browseId: String, val title: String, val subtitle: String = "", val imageUrl: String? = null)
@Serializable data class MoodRoute(val title: String, val browseId: String, val params: String? = null)
@Serializable data object WrappedRoute

fun NavGraphBuilder.discoveryScreens(nav: FreeNavigation) {
    composable<YtArtistRoute> { ArtistScreen(nav) }
    composable<YtCollectionRoute> { CollectionSongsScreen(nav) }
    composable<MoodRoute> { MoodScreen(nav) }
    composable<WrappedRoute> { WrappedScreen(nav) }
}

/** Loading → data or a message. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data object Failed : Load<Nothing>
}

@Composable
private fun BackHeader(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        actions()
    }
}

@Composable
private fun PlayRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
            Text(stringResource(R.string.free_play), Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
            Text(stringResource(R.string.free_shuffle), Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

// ---------------------------------------------------------------------------------------------
// Artist page
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: InnerTubeCatalogRepository,
    val freePlayer: FreePlayer,
) : ViewModel() {
    val route = savedState.toRoute<YtArtistRoute>()
    private val _state = MutableStateFlow<Load<YtArtistPage>>(Load.Loading)
    val state: StateFlow<Load<YtArtistPage>> = _state.asStateFlow()
    private val _image = MutableStateFlow(route.imageUrl)
    val image: StateFlow<String?> = _image.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            val id = route.channelId ?: catalog.findArtist(route.name).getOrNull()?.also { a ->
                if (_image.value == null) _image.value = a.images.firstOrNull()?.url
            }?.id
            val page = id?.let { catalog.artist(it).getOrNull() }
            _state.value = if (page != null && (page.songs.isNotEmpty() || page.albums.isNotEmpty())) Load.Ready(page) else Load.Failed
        }
    }

    fun play(songs: List<YouTubeVideo>, index: Int) = freePlayer.playYouTube(songs, index)
}

@Composable
fun ArtistScreen(nav: FreeNavigation, viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val image by viewModel.image.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader(viewModel.route.name, onBack = nav.onBack)
        when (val s = state) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Load.Failed -> MessageState(Icons.Rounded.Person, stringResource(R.string.discovery_artist_failed), null, actionLabel = stringResource(R.string.spotify_retry), onAction = viewModel::load)
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverImage(image, null, Modifier.size(160.dp), shape = CircleShape, placeholderIcon = Icons.Rounded.Person)
                    }
                    if (s.data.songs.isNotEmpty()) {
                        PlayRow(
                            onPlay = { viewModel.play(s.data.songs, 0); nav.onYouTubePlayer() },
                            onShuffle = { viewModel.play(s.data.songs.shuffled(), 0); nav.onYouTubePlayer() },
                        )
                    }
                }
                if (s.data.songs.isNotEmpty()) item(key = "songsH") { SectionTitle(stringResource(R.string.discovery_top_songs)) }
                itemsIndexed(s.data.songs.take(10), key = { i, v -> "s$i${v.id}" }) { i, v ->
                    YouTubeRow(v, isCurrent = false) { viewModel.play(s.data.songs, i); nav.onYouTubePlayer() }
                }
                if (s.data.albums.isNotEmpty()) item(key = "albums") {
                    Shelf(stringResource(R.string.discovery_albums), s.data.albums, key = { it.id }) { a ->
                        BigCard(a.name, a.releaseYear, a.images.firstOrNull()?.url, {
                            nav.onYtCollection(a.id, a.name, viewModel.route.name, a.images.firstOrNull()?.url)
                        }, placeholderIcon = Icons.Rounded.Album)
                    }
                }
                if (s.data.similar.isNotEmpty()) item(key = "similar") {
                    Shelf(stringResource(R.string.discovery_fans_also_like), s.data.similar, key = { it.id }) { a ->
                        BigCard(a.name, null, a.images.firstOrNull()?.url, { nav.onArtist(a.name, a.id, a.images.firstOrNull()?.url) }, circle = true, placeholderIcon = Icons.Rounded.Person)
                    }
                }
                if (s.data.playlists.isNotEmpty()) item(key = "playlists") {
                    Shelf(stringResource(R.string.discovery_featured_on), s.data.playlists, key = { it.id }) { p ->
                        val subtitle = playlistSubtitle(p.owner.displayName)
                        BigCard(p.name, subtitle, p.images.firstOrNull()?.url, { nav.onYtCollection(p.id, p.name, subtitle, p.images.firstOrNull()?.url) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Album / playlist songs
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class CollectionSongsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: InnerTubeCatalogRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val route = savedState.toRoute<YtCollectionRoute>()
    private val _state = MutableStateFlow<Load<List<YouTubeVideo>>>(Load.Loading)
    val state: StateFlow<Load<List<YouTubeVideo>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            val songs = catalog.collectionSongs(route.browseId).getOrNull()
            _state.value = if (!songs.isNullOrEmpty()) Load.Ready(songs) else Load.Failed
        }
    }

    fun play(songs: List<YouTubeVideo>, index: Int) = freePlayer.playYouTube(songs, index)
}

@Composable
fun CollectionSongsScreen(nav: FreeNavigation, viewModel: CollectionSongsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader(viewModel.route.title, viewModel.route.subtitle, onBack = nav.onBack)
        when (val s = state) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Load.Failed -> MessageState(Icons.Rounded.Album, stringResource(R.string.free_no_tracks), null, actionLabel = stringResource(R.string.spotify_retry), onAction = viewModel::load)
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                item(key = "head") {
                    viewModel.route.imageUrl?.let {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CoverImage(it, null, Modifier.size(180.dp), shape = RoundedCornerShape(6.dp))
                        }
                    }
                    PlayRow(
                        onPlay = { viewModel.play(s.data, 0); nav.onYouTubePlayer() },
                        onShuffle = { viewModel.play(s.data.shuffled(), 0); nav.onYouTubePlayer() },
                    )
                }
                itemsIndexed(s.data, key = { i, v -> "c$i${v.id}" }) { i, v ->
                    YouTubeRow(v, isCurrent = false) { viewModel.play(s.data, i); nav.onYouTubePlayer() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Moods & genres
// ---------------------------------------------------------------------------------------------

@HiltViewModel
class MoodsViewModel @Inject constructor(private val catalog: InnerTubeCatalogRepository) : ViewModel() {
    private val _moods = MutableStateFlow<List<YtMood>>(emptyList())
    val moods: StateFlow<List<YtMood>> = _moods.asStateFlow()

    init { viewModelScope.launch { _moods.value = catalog.moods().getOrDefault(emptyList()) } }
}

/** Colour of a mood tile: YouTube's own stripe colour, else one from a fixed palette. */
internal fun moodColor(mood: YtMood, index: Int): Color =
    mood.color?.let { Color(it or 0xFF000000) }?.takeIf { it != Color.Black }
        ?: MOOD_PALETTE[index % MOOD_PALETTE.size]

private val MOOD_PALETTE = listOf(Color(0xFF8D67AB), Color(0xFF1E3264), Color(0xFFE8115B), Color(0xFF148A08), Color(0xFFBC5900), Color(0xFF0D73EC), Color(0xFF503750), Color(0xFF477D95))

@HiltViewModel
class MoodViewModel @Inject constructor(savedState: SavedStateHandle, private val catalog: InnerTubeCatalogRepository) : ViewModel() {
    val route = savedState.toRoute<MoodRoute>()
    private val _state = MutableStateFlow<Load<List<Playlist>>>(Load.Loading)
    val state: StateFlow<Load<List<Playlist>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            val lists = catalog.moodPlaylists(YtMood(route.title, route.browseId, route.params)).getOrNull()
            _state.value = if (!lists.isNullOrEmpty()) Load.Ready(lists) else Load.Failed
        }
    }
}

@Composable
fun MoodScreen(nav: FreeNavigation, viewModel: MoodViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackHeader(viewModel.route.title, onBack = nav.onBack)
        when (val s = state) {
            Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Load.Failed -> MessageState(Icons.Rounded.AutoAwesome, stringResource(R.string.discovery_mood_failed), null, actionLabel = stringResource(R.string.spotify_retry), onAction = viewModel::load)
            is Load.Ready -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomContentPadding.current + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(s.data, key = { it.id }) { p ->
                    BigCard(p.name, playlistSubtitle(p.owner.displayName), p.images.firstOrNull()?.url, {
                        nav.onYtCollection(p.id, p.name, viewModel.route.title, p.images.firstOrNull()?.url)
                    })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Made for you (Home)
// ---------------------------------------------------------------------------------------------

data class MadeForYou(
    val topArtists: List<ArtistStat> = emptyList(),
    val becauseArtist: String? = null,
    val becauseSongs: List<YouTubeVideo> = emptyList(),
    val rediscover: List<SongStat> = emptyList(),
)

@HiltViewModel
class MadeForYouViewModel @Inject constructor(
    private val playLog: PlayLogRepository,
    private val liked: LikedMediaRepository,
    private val youTube: YouTubeRepository,
    private val lastFm: LastFmRepository,
    val freePlayer: FreePlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(MadeForYou())
    val state: StateFlow<MadeForYou> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val month = System.currentTimeMillis() - 30 * PlayLogRepository.DAY_MS
            val top = runCatching { playLog.topArtists(month, 10) }.getOrDefault(emptyList())
            val rediscover = runCatching { playLog.forgotten(quietDays = 14, limit = 15) }.getOrDefault(emptyList())
            _state.value = MadeForYou(topArtists = top, rediscover = rediscover)

            // "Because you liked <artist>": the artist you liked most, and songs by similar artists.
            val likedSongs = runCatching { liked.liked.first() }.getOrDefault(emptyList())
            val artist = likedSongs.map { it.artist.substringBefore(',').trim() }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: top.firstOrNull()?.name ?: return@launch
            val seed = likedSongs.firstNotNullOfOrNull { it.toYouTubeVideo()?.takeIf { v -> v.artist.equals(artist, true) } }
                ?: runCatching { youTube.search(artist).firstOrNull() }.getOrNull() ?: return@launch
            val similar = runCatching { lastFm.similarArtists(artist, limit = 5) }.getOrDefault(emptyList())
            if (similar.isEmpty()) return@launch
            val songs = runCatching { youTube.radio(seed, similar) }.getOrDefault(emptyList())
                .filterNot { it.artist.equals(artist, ignoreCase = true) }.take(15)
            _state.value = _state.value.copy(becauseArtist = artist, becauseSongs = songs)
        }
    }
}

/** "Your top artists" and "Because you liked …" (above Trending on Home). */
@Composable
fun MadeForYouTopShelves(nav: FreeNavigation, viewModel: MadeForYouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column {
        Shelf(stringResource(R.string.discovery_your_top_artists), state.topArtists, key = { "ta:" + it.name }) { a ->
            BigCard(a.name, androidx.compose.ui.res.pluralStringResource(R.plurals.wrapped_plays, a.plays, a.plays), a.artworkUrl, { nav.onArtist(a.name, null, null) }, circle = true, placeholderIcon = Icons.Rounded.Person)
        }
        state.becauseArtist?.let { artist ->
            Shelf(stringResource(R.string.discovery_because_you_liked, artist), state.becauseSongs, key = { "bl:" + it.id }) { v ->
                BigCard(v.songTitle, v.artist, v.thumbnailUrl, {
                    viewModel.freePlayer.playYouTube(state.becauseSongs, state.becauseSongs.indexOf(v)); nav.onYouTubePlayer()
                })
            }
        }
    }
}

/** "Rediscover": songs you played a lot but not lately (lower on Home). */
@Composable
fun RediscoverShelf(nav: FreeNavigation, viewModel: MadeForYouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val songs = state.rediscover.map { it.media }
    Shelf(stringResource(R.string.discovery_rediscover), songs, key = { "rd:" + it.mediaId }) { m ->
        BigCard(m.title, m.artist, m.artworkUrl, {
            if (viewModel.freePlayer.playMixed(songs, songs.indexOf(m))) nav.onYouTubePlayer()
        })
    }
}

// ---------------------------------------------------------------------------------------------
// Spotineil Wrapped
// ---------------------------------------------------------------------------------------------

enum class WrappedPeriod(val label: Int) { Month(R.string.wrapped_month), Year(R.string.wrapped_year), All(R.string.wrapped_all) }

@HiltViewModel
class WrappedViewModel @Inject constructor(private val playLog: PlayLogRepository) : ViewModel() {
    private val _period = MutableStateFlow(WrappedPeriod.Month)
    val period: StateFlow<WrappedPeriod> = _period.asStateFlow()
    private val _summary = MutableStateFlow<ListeningSummary?>(null)
    val summary: StateFlow<ListeningSummary?> = _summary.asStateFlow()

    init { select(WrappedPeriod.Month) }

    fun select(p: WrappedPeriod) {
        _period.value = p
        _summary.value = null
        viewModelScope.launch { _summary.value = playLog.summary(since(p)) }
    }

    private fun since(p: WrappedPeriod): Long {
        val c = Calendar.getInstance()
        return when (p) {
            WrappedPeriod.Month -> c.apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            WrappedPeriod.Year -> c.apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            WrappedPeriod.All -> 0L
        }
    }
}

@Composable
fun WrappedScreen(nav: FreeNavigation, viewModel: WrappedViewModel = hiltViewModel()) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.wrapped_title), onBack = nav.onBack) {
            val s = summary
            if (s != null && s.minutes > 0) {
                IconButton(onClick = { shareWrapped(context, s, context.getString(period.label)) }) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.wrapped_share))
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WrappedPeriod.entries.forEach { p ->
                FilterChip(selected = p == period, onClick = { viewModel.select(p) }, label = { Text(stringResource(p.label)) })
            }
        }
        val s = summary
        when {
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.minutes == 0L -> MessageState(Icons.Rounded.AutoAwesome, stringResource(R.string.wrapped_empty), null)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                item(key = "stats") {
                    Box(
                        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFE8115B))))
                            .padding(20.dp),
                    ) {
                        Column {
                            Text(stringResource(R.string.wrapped_minutes_label), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
                            Text("%,d".format(s.minutes), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                            Text(stringResource(R.string.wrapped_songs_count, s.songCount), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            s.topArtists.firstOrNull()?.let {
                                Text(stringResource(R.string.wrapped_top_artist_is, it.name), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                item(key = "songsH") { SectionTitle(stringResource(R.string.wrapped_top_songs)) }
                itemsIndexed(s.topSongs, key = { _, st -> "ws" + st.media.mediaId }) { i, st ->
                    RankRow(i + 1, st.media.artworkUrl, st.media.title, "${st.media.artist} • ${context.resources.getQuantityString(R.plurals.wrapped_plays, st.plays, st.plays)}", circle = false)
                }
                item(key = "artistsH") { SectionTitle(stringResource(R.string.wrapped_top_artists)) }
                itemsIndexed(s.topArtists, key = { _, a -> "wa" + a.name }) { i, a ->
                    Box(Modifier.clickable { nav.onArtist(a.name, null, null) }) {
                        RankRow(i + 1, a.artworkUrl, a.name, context.resources.getQuantityString(R.plurals.wrapped_plays, a.plays, a.plays), circle = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, image: String?, title: String, subtitle: String, circle: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        CoverImage(image, null, Modifier.size(48.dp), shape = if (circle) CircleShape else RoundedCornerShape(4.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Draws a shareable Wrapped card and opens the share sheet with it. */
private fun shareWrapped(context: Context, s: ListeningSummary, periodLabel: String) {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF450AF5.toInt(), 0xFFE8115B.toInt(), Shader.TileMode.CLAMP)
    })
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    fun text(t: String, x: Float, y: Float, size: Float, bold: Boolean = false, alpha: Int = 255) {
        white.textSize = size
        white.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        white.alpha = alpha
        c.drawText(t.take(36), x, y, white)
    }
    text("Spotineil Wrapped", 80f, 150f, 64f, bold = true)
    text(periodLabel, 80f, 220f, 44f, alpha = 210)
    text(context.getString(R.string.wrapped_minutes_label), 80f, 360f, 44f, alpha = 210)
    text("%,d".format(s.minutes), 80f, 500f, 150f, bold = true)
    text(context.getString(R.string.wrapped_top_songs), 80f, 640f, 48f, bold = true)
    s.topSongs.take(5).forEachIndexed { i, st -> text("${i + 1}. ${st.media.title}", 80f, 720f + i * 70f, 44f) }
    text(context.getString(R.string.wrapped_top_artists), 80f, 1110f, 48f, bold = true)
    text(s.topArtists.take(3).joinToString(" · ") { it.name }, 80f, 1185f, 42f)
    text("github.com/freshmens12/Spotineil", 80f, 1290f, 34f, alpha = 180)

    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "spotineil-wrapped.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.wrapped_share_text, s.minutes, s.topArtists.firstOrNull()?.name.orEmpty()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.wrapped_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Under a playlist: its owner, or just "Playlist" when it is the catalog's own. */
@Composable
private fun playlistSubtitle(owner: String?): String {
    val o = owner?.trim().orEmpty()
    return if (o.isEmpty() || o.contains("YouTube", ignoreCase = true)) stringResource(R.string.discovery_playlist) else o
}
