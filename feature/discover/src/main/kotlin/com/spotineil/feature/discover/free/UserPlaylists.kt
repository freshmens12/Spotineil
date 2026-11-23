@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spotineil.feature.discover.free

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.spotineil.core.data.repository.UserPlaylistRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.designsystem.theme.dialogContainer
import com.spotineil.core.designsystem.theme.menuContainer
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.ui.PlaylistNameDialog
import com.spotineil.core.ui.SongActionsSheet
import com.spotineil.feature.discover.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Serializable data class UserPlaylistRoute(val id: String)

@HiltViewModel
class UserPlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: UserPlaylistRepository,
    private val freePlayer: FreePlayer,
) : ViewModel() {
    val id = savedState.toRoute<UserPlaylistRoute>().id
    val playlist = repository.playlist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val songs: StateFlow<List<PlayableMedia>?> = repository.songs(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when a YouTube song started (the caller then opens the YouTube player). */
    fun play(list: List<PlayableMedia>, index: Int): Boolean = freePlayer.playMixed(list, index)
    fun rename(name: String) = viewModelScope.launch { repository.rename(id, name) }
    fun delete() = viewModelScope.launch { repository.delete(id) }
    fun remove(media: PlayableMedia) = viewModelScope.launch { repository.remove(id, media.mediaId) }
    fun move(from: Int, to: Int) = viewModelScope.launch { repository.move(id, from, to) }
    suspend fun radio(list: List<PlayableMedia>): Boolean = freePlayer.playRadioFrom(list)
}

@Composable
fun UserPlaylistScreen(nav: FreeNavigation, viewModel: UserPlaylistViewModel = hiltViewModel()) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var songMenu by remember { mutableStateOf<PlayableMedia?>(null) }
    // Local copy while dragging, so rows move smoothly before the database catches up.
    var order by remember(songs) { mutableStateOf(songs.orEmpty()) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.free_back)) }
            Column(Modifier.weight(1f)) {
                Text(playlist?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.playlist_song_count, order.size, order.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (order.isNotEmpty()) PlaylistRadioButton(nav) { viewModel.radio(order) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.playlist_options)) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, containerColor = menuContainer()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { menuOpen = false; dialog = "rename" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { menuOpen = false; dialog = "delete" })
                }
            }
        }

        val list = order
        if (songs != null && list.isEmpty()) {
            MessageState(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.playlist_empty_title), stringResource(R.string.playlist_empty_body))
        } else {
            val listState = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                // Index 0 is the Play/Shuffle row.
                val f = from.index - 1
                val t = to.index - 1
                if (f in list.indices && t in list.indices) {
                    order = list.toMutableList().apply { add(t, removeAt(f)) }
                    viewModel.move(f, t)
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (viewModel.play(list, 0)) nav.onYouTubePlayer() }, enabled = list.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.free_play), Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = { if (viewModel.play(list.shuffled(), 0)) nav.onYouTubePlayer() }, enabled = list.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.free_shuffle), Modifier.padding(start = 6.dp))
                        }
                    }
                }
                itemsIndexed(list, key = { _, m -> m.mediaId }) { i, m ->
                    ReorderableItem(reorder, key = m.mediaId) { dragging ->
                        Surface(color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = { if (viewModel.play(list, i)) nav.onYouTubePlayer() }, onLongClick = { songMenu = m })
                                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverImage(m.artworkUrl, null, Modifier.size(48.dp), shape = RoundedCornerShape(4.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(m.artist, formatDuration(m.durationMs).takeIf { m.durationMs > 0 }.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                    Icon(Icons.Rounded.DragHandle, stringResource(R.string.playlist_drag))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    songMenu?.let { m ->
        SongActionsSheet(m, onDismiss = { songMenu = null }, onMessage = nav.onMessage, onRemoveFromPlaylist = { viewModel.remove(m) })
    }
    when (dialog) {
        "rename" -> PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initial = playlist?.name.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { viewModel.rename(it); dialog = null },
        )
        "delete" -> AlertDialog(
            containerColor = dialogContainer(),
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_body, playlist?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { dialog = null; viewModel.delete(); nav.onBack() }) {
                    Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
}
