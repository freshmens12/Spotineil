package com.spotineil.feature.discover.free

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.PodcastRepository
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.RecentItem
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FreeLibraryUiState(
    val likedCount: Int = 0,
    val podcasts: List<Podcast> = emptyList(),
    val stations: List<RadioStation> = emptyList(),
    /** Albums/concerts opened recently (there's no "save album" without an account). */
    val collections: List<ArchiveCollection> = emptyList(),
)

enum class LibraryFilter(val label: Int) {
    All(R.string.free_filter_all),
    Podcasts(R.string.free_filter_podcasts),
    Stations(R.string.free_filter_stations),
    Albums(R.string.free_filter_albums),
}

@HiltViewModel
class FreeLibraryViewModel @Inject constructor(
    liked: LikedMediaRepository,
    podcasts: PodcastRepository,
    radio: RadioRepository,
    recents: RecentsRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val state: StateFlow<FreeLibraryUiState> = combine(liked.liked, podcasts.followed, radio.favorites, recents.recent(50)) { l, p, s, r ->
        FreeLibraryUiState(l.size, p, s, r.filterIsInstance<RecentItem.Collection>().map { it.collection })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FreeLibraryUiState())

    fun playStation(station: RadioStation) = freePlayer.playStation(station)
}

@Composable
fun FreeLibraryRoute(nav: FreeNavigation, viewModel: FreeLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    val show = { f: LibraryFilter -> filter == LibraryFilter.All || filter == f }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        item(key = "title") { ScreenTitle(stringResource(R.string.free_library_title), nav.onSettings, Modifier.statusBarsPadding()) }
        item(key = "filters") {
            FilterPills(LibraryFilter.entries, filter, { stringResource(it.label) }, { filter = it }, Modifier.padding(bottom = 8.dp))
        }
        if (filter == LibraryFilter.All) {
            item(key = "liked") {
                LibraryRow(stringResource(R.string.free_liked_songs), androidx.compose.ui.res.pluralStringResource(R.plurals.free_liked_count, state.likedCount, state.likedCount), nav.onLikedSongs) { m ->
                    LikedArt(m)
                }
            }
            item(key = "device") {
                LibraryRow(stringResource(R.string.free_on_device), stringResource(R.string.free_on_device_desc), nav.onLocalMusic) { m ->
                    Box(m.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        if (show(LibraryFilter.Podcasts)) {
            items(state.podcasts, key = { "p:" + it.feedUrl }) { p ->
                LibraryRow(p.title, stringResource(R.string.free_type_podcast, p.author), { nav.onPodcast(p) }) { m ->
                    CoverImage(p.artworkUrl, null, m, placeholderIcon = Icons.Rounded.Podcasts)
                }
            }
        }
        if (show(LibraryFilter.Stations)) {
            items(state.stations, key = { "s:" + it.id }) { s ->
                LibraryRow(s.name, stringResource(R.string.free_type_station, s.subtitle), { viewModel.playStation(s) }, circle = true) { m ->
                    CoverImage(s.faviconUrl, null, m, placeholderIcon = Icons.Rounded.Radio)
                }
            }
        }
        if (show(LibraryFilter.Albums)) {
            items(state.collections, key = { "c:" + it.id }) { c -> CollectionRow(c) { nav.onCollection(c) } }
        }
        val nothing = when (filter) {
            LibraryFilter.All -> false
            LibraryFilter.Podcasts -> state.podcasts.isEmpty()
            LibraryFilter.Stations -> state.stations.isEmpty()
            LibraryFilter.Albums -> state.collections.isEmpty()
        }
        if (nothing || (filter == LibraryFilter.All && state.podcasts.isEmpty() && state.stations.isEmpty() && state.collections.isEmpty() && state.likedCount == 0)) {
            item(key = "empty") { MessageState(Icons.Rounded.LibraryMusic, stringResource(R.string.free_library_empty), null) }
        }
    }
}
