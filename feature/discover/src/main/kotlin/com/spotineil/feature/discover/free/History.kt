@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spotineil.feature.discover.free

import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.RecentItem
import com.spotineil.core.model.toPlayable
import com.spotineil.core.model.toYouTubeVideo
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The songs you played, most recent first (a song played again moves back to the top). */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val recents: RecentsRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {

    val songs: StateFlow<List<PlayableMedia>?> = recents.recent(HISTORY_SIZE)
        .map { items ->
            items.filterIsInstance<RecentItem.Song>().map { it.media }
                // Songs saved by older versions: play them the current way.
                .map { m -> m.toYouTubeVideo()?.toPlayable() ?: m }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowPlayingId = freePlayer.nowPlayingId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when the tapped song is a main-catalog one (the caller then opens the player screen). */
    fun play(list: List<PlayableMedia>, index: Int): Boolean = freePlayer.playMixed(list, index)

    fun clear() = viewModelScope.launch { recents.clear() }

    private companion object {
        const val HISTORY_SIZE = 500
    }
}

@Composable
fun HistoryScreen(nav: FreeNavigation, viewModel: HistoryViewModel = hiltViewModel()) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val all = songs.orEmpty()
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val list = all.matching(filter)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
                Text(
                    stringResource(R.string.free_history),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (list.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.free_history_clear)) }
                }
            }
        }
        if (all.size > 5) item(key = "filter") { ListFilterField(filter, { filter = it }, stringResource(R.string.free_find_in_list)) }
        if (songs != null && all.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.free_history_empty),
                    Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(list, key = { _, m -> m.mediaId }) { i, m ->
            HistoryRow(m, nowPlaying == m.mediaId) { if (viewModel.play(list, i)) nav.onYouTubePlayer() }
        }
    }
}

@Composable
private fun HistoryRow(media: PlayableMedia, isCurrent: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val longClick = onLongClick ?: com.spotineil.core.ui.LocalSongMenu.current?.let { open -> { open(media) } }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = longClick).padding(horizontal = 16.dp, vertical = 6.dp),
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
            Text(
                media.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Round icon used by the Library row. */
@Composable
internal fun HistoryArt(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    }
}
