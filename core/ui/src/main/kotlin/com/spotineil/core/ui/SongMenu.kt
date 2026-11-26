package com.spotineil.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.LikedMediaRepository
import com.spotineil.core.data.repository.UserPlaylist
import com.spotineil.core.data.repository.UserPlaylistRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.theme.dialogContainer
import com.spotineil.core.designsystem.theme.sheetContainer
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SongActionsViewModel @Inject constructor(
    private val player: PlaybackController,
    private val liked: LikedMediaRepository,
    private val playlistRepo: UserPlaylistRepository,
    private val youTubeDownloader: com.spotineil.core.player.youtube.YouTubeDownloader,
) : ViewModel() {
    val playlists: StateFlow<List<UserPlaylist>> = playlistRepo.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isLiked(mediaId: String): Flow<Boolean> = liked.isLiked(mediaId)
    fun canDownload(media: PlayableMedia): Boolean = youTubeDownloader.canDownload(media)
    fun download(media: PlayableMedia) = youTubeDownloader.download(media)
    fun setLiked(media: PlayableMedia, value: Boolean) = viewModelScope.launch { liked.setLiked(media, value) }
    fun playNext(songs: List<PlayableMedia>) = player.playNext(songs)
    fun addToQueue(songs: List<PlayableMedia>) = player.addToQueueEnd(songs)

    fun addToPlaylist(id: String, songs: List<PlayableMedia>, done: (Int) -> Unit) = viewModelScope.launch {
        done(playlistRepo.add(id, songs))
    }

    fun createPlaylist(name: String, songs: List<PlayableMedia>, done: () -> Unit) = viewModelScope.launch {
        playlistRepo.create(name, songs)
        done()
    }
}

/**
 * Options for one song: Play next, Add to queue, Add to playlist, Download (YouTube songs), Like. Opened with a long press
 * on a song (or its ⋮ button). [onRemoveFromPlaylist] adds "Remove from this playlist".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    media: PlayableMedia,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Opens the artist's page (shown when given and the song has an artist). */
    onGoToArtist: ((String) -> Unit)? = null,
    /** Saves a free song whose artist or licence allows it (YouTube songs are handled here). */
    onDownload: (() -> Unit)? = null,
    viewModel: SongActionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        AddToPlaylistSheet(listOf(media), onDismiss = onDismiss, onMessage = onMessage, viewModel = viewModel)
        return
    }
    val isLiked by remember(media.mediaId) { viewModel.isLiked(media.mediaId) }.collectAsStateWithLifecycle(false)
    ModalBottomSheet(containerColor = sheetContainer(), onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(media.artworkUrl, null, Modifier.size(48.dp), shape = RoundedCornerShape(4.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(media.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(media.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (!media.isLive) {
                ActionRow(Icons.Rounded.PlaylistPlay, stringResource(R.string.ui_play_next)) {
                    viewModel.playNext(listOf(media)); onMessage(context.getString(R.string.ui_will_play_next)); onDismiss()
                }
                ActionRow(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.ui_add_queue)) {
                    viewModel.addToQueue(listOf(media)); onMessage(context.getString(R.string.ui_added_queue)); onDismiss()
                }
                ActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.ui_add_playlist)) { picking = true }
            }
            if (onDownload != null || viewModel.canDownload(media)) {
                ActionRow(Icons.Rounded.Download, stringResource(R.string.ui_download)) {
                    if (onDownload != null) onDownload() else viewModel.download(media)
                    onDismiss()
                }
            }
            ActionRow(
                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (isLiked) R.string.ui_remove_liked else R.string.ui_add_liked),
            ) {
                viewModel.setLiked(media, !isLiked)
                onMessage(context.getString(if (isLiked) R.string.ui_removed_liked else R.string.ui_added_liked))
                onDismiss()
            }
            val artist = media.artist.substringBefore(",").trim()
            if (onGoToArtist != null && artist.isNotBlank() && !media.isLive) {
                ActionRow(Icons.Rounded.Person, stringResource(R.string.ui_go_artist)) { onGoToArtist(artist); onDismiss() }
            }
            if (onRemoveFromPlaylist != null) {
                ActionRow(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.ui_remove_playlist)) { onRemoveFromPlaylist(); onDismiss() }
            }
        }
    }
}

/** Picks one of "Your playlists" (or makes a new one) and adds [songs] to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    songs: List<PlayableMedia>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: SongActionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.ui_new_playlist),
            initial = "",
            onDismiss = { naming = false },
            onConfirm = { name ->
                viewModel.createPlaylist(name, songs) {
                    onMessage(context.getString(R.string.ui_added_playlist))
                    onDismiss()
                }
            },
        )
    }
    ModalBottomSheet(containerColor = sheetContainer(), onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().heightIn(min = 200.dp)) {
            Text(stringResource(R.string.ui_choose_playlist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ActionRow(Icons.Rounded.Add, stringResource(R.string.ui_new_playlist)) { naming = true }
            LazyColumn {
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.addToPlaylist(p.id, songs) { added ->
                                onMessage(
                                    if (added > 0) context.getString(R.string.ui_added_to_named, p.name)
                                    else context.getString(R.string.ui_already_in_playlist, p.name),
                                )
                                onDismiss()
                            }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(p.coverUrl, null, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.ui_song_count, p.songCount, p.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Asks for a playlist name (new playlist or rename). */
@Composable
fun PlaylistNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        containerColor = dialogContainer(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.ui_playlist_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Opens the song menu ([SongActionsSheet]) for a song. The app provides it once; song rows use it
 * for a long press, so every list gets the menu without wiring it screen by screen.
 */
val LocalSongMenu = androidx.compose.runtime.staticCompositionLocalOf<((PlayableMedia) -> Unit)?> { null }

/** Like [LocalSongMenu], for a free song that can be downloaded: the menu gets a Download row running the given action. */
val LocalSongMenuWithDownload = androidx.compose.runtime.staticCompositionLocalOf<((PlayableMedia, () -> Unit) -> Unit)?> { null }

/** The ⋮ button at the end of a song row: opens the same menu as a long press. Nothing when [onClick] is null. */
@Composable
fun SongMenuButton(onClick: (() -> Unit)?) {
    if (onClick == null) return
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.testTag("song_menu")) {
        Icon(Icons.Rounded.MoreVert, stringResource(R.string.ui_more_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
