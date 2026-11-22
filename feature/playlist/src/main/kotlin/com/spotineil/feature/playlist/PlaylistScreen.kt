package com.spotineil.feature.playlist

import com.spotineil.core.designsystem.theme.dialogContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.DetailScaffold
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.errorMessage
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.PlaylistTrack
import com.spotineil.core.ui.TrackActionTarget
import com.spotineil.core.ui.TrackActionsSheet
import com.spotineil.core.ui.toActionTarget
import kotlinx.serialization.Serializable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Serializable
data class PlaylistRoute(val id: String)

fun NavController.navigateToPlaylist(id: String) = navigate(PlaylistRoute(id))

fun NavGraphBuilder.playlistScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onOwnerClick: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    composable<PlaylistRoute> { PlaylistRoute(onBack, onArtistClick, onAlbumClick, onOwnerClick, onMessage) }
}

@Composable
fun PlaylistRoute(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onOwnerClick: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val nowPlaying by viewModel.nowPlayingUri.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Pair<TrackActionTarget, PlaylistTrack>?>(null) }
    var editDetails by rememberSaveable { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<Throwable?>(null) }
    val savedMsg = stringResource(R.string.playlist_saved)

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                PlaylistEvent.Saved -> onMessage(savedMsg)
                is PlaylistEvent.Failed -> pendingError = e.error
            }
        }
    }
    pendingError?.let { err -> val msg = errorMessage(err); LaunchedEffect(err) { onMessage(msg); pendingError = null } }

    state.editItems?.let { items ->
        PlaylistEditor(
            items = items,
            onBack = viewModel::closeEditor,
            onMove = viewModel::moveLocal,
            onDragStart = viewModel::onDragStart,
            onDragEnd = viewModel::onDragEnd,
            onRemove = viewModel::remove,
        )
        return
    }

    val playlist = state.playlist
    DetailScaffold(
        title = playlist?.name.orEmpty(),
        imageUrl = playlist?.let { rememberImageUrl(it.images, 640) },
        onBack = onBack,
        headerMeta = {
            if (playlist != null) {
                playlist.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                TextButton(onClick = { onOwnerClick(playlist.owner.id) }, contentPadding = PaddingValues(0.dp)) {
                    Text(playlist.owner.displayName ?: playlist.owner.id, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    listOfNotNull(
                        stringResource(R.string.playlist_songs, playlist.trackCount),
                        if (playlist.collaborative) stringResource(R.string.playlist_collaborative)
                        else if (playlist.isPublic == true) stringResource(R.string.playlist_public)
                        else stringResource(R.string.playlist_private),
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actionRow = {
            if (playlist != null) {
                if (!state.isOwner) {
                    IconButton(onClick = viewModel::toggleFollow) {
                        Icon(
                            if (state.isFollowing) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                            stringResource(if (state.isFollowing) R.string.playlist_unfollow else R.string.playlist_follow),
                            tint = if (state.isFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (state.isOwner) {
                    IconButton(onClick = { editDetails = true }) { Icon(Icons.Rounded.Edit, stringResource(R.string.playlist_edit_details)) }
                }
                if (state.canEdit) {
                    IconButton(onClick = viewModel::openEditor, enabled = !state.editLoading) {
                        if (state.editLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.SwapVert, stringResource(R.string.playlist_reorder))
                    }
                }
            }
        },
        onPlay = playlist?.let { { viewModel.play() } },
        onShuffle = playlist?.let { { viewModel.play(shuffle = true) } },
    ) {
        when {
            playlist == null && state.error != null -> item { ErrorState(state.error!!, onRetry = { viewModel.load() }) }
            else -> {
                val refresh = tracks.loadState.refresh
                if (tracks.itemCount == 0 && refresh is LoadState.Loading) items(8) { SkeletonTrackRow() }
                if (tracks.itemCount == 0 && refresh is LoadState.Error) item { ErrorState(refresh.error, onRetry = { tracks.retry() }) }
                items(tracks.itemCount, key = { i -> tracks.peek(i)?.let { "${it.position}:${it.track.uri}" } ?: i }) { i ->
                    tracks[i]?.let { pt ->
                        val t = pt.track
                        TrackRow(
                            title = t.name,
                            subtitle = t.artistNames,
                            onClick = { viewModel.play(index = pt.position) },
                            imageUrl = rememberImageUrl(t.images, 120),
                            explicit = t.explicit,
                            isCurrent = t.uri == nowPlaying,
                            enabled = t.isPlayable && !t.isLocal,
                            onMore = { target = t.toActionTarget(removable = state.canEdit) to pt },
                        )
                    }
                }
            }
        }
    }

    target?.let { (actionTarget, pt) ->
        TrackActionsSheet(
            target = actionTarget,
            onDismiss = { target = null },
            onGoToArtist = onArtistClick,
            onGoToAlbum = onAlbumClick,
            onMessage = onMessage,
            onRemoveFromPlaylist = { viewModel.removeFromPaged(pt) },
        )
    }

    if (editDetails && playlist != null) {
        EditDetailsDialog(
            initialName = playlist.name,
            initialDescription = playlist.description.orEmpty(),
            initialPublic = playlist.isPublic == true,
            initialCollaborative = playlist.collaborative,
            onDismiss = { editDetails = false },
            onSave = { n, d, p, c -> editDetails = false; viewModel.updateDetails(n, d, p, c) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEditor(
    items: List<PlaylistTrack>,
    onBack: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDragStart: (String) -> Unit,
    onDragEnd: (String) -> Unit,
    onRemove: (PlaylistTrack) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to -> onMove(from.index, to.index) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playlist_reorder)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.playlist_done)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            items(items, key = { it.key() }) { item ->
                ReorderableItem(reorderState, key = item.key()) { dragging ->
                    Surface(tonalElevation = if (dragging) 8.dp else 0.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.playlist_remove), tint = MaterialTheme.colorScheme.error)
                            }
                            CoverImage(rememberImageUrl(item.track.images, 120), null, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.track.artistNames, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { onDragStart(item.key()) },
                                    onDragStopped = { onDragEnd(item.key()) },
                                ),
                            ) {
                                Icon(Icons.Rounded.DragHandle, stringResource(R.string.playlist_drag))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditDetailsDialog(
    initialName: String,
    initialDescription: String,
    initialPublic: Boolean,
    initialCollaborative: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var public by rememberSaveable { mutableStateOf(initialPublic) }
    var collaborative by rememberSaveable { mutableStateOf(initialCollaborative) }
    AlertDialog(containerColor = dialogContainer(), 
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.playlist_description)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playlist_public), Modifier.weight(1f))
                    Switch(public && !collaborative, { public = it }, enabled = !collaborative)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playlist_collaborative), Modifier.weight(1f))
                    Switch(collaborative, { collaborative = it; if (it) public = false })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, description, public, collaborative) }) {
                Text(stringResource(R.string.playlist_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) } },
    )
}
