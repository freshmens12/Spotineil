package com.spotineil.feature.library

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.spotineil.core.designsystem.theme.dialogContainer
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotineil.core.designsystem.component.MediaCard
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.LocalTrack

/** A group of local tracks (an album or an artist). */
data class LocalGroup(val title: String, val subtitle: String, val artUri: String?, val tracks: List<LocalTrack>)

internal fun List<LocalTrack>.albums(): List<LocalGroup> =
    groupBy { it.album.ifBlank { "Unknown album" } to it.artist }
        .map { (key, tracks) -> LocalGroup(key.first, key.second, tracks.firstNotNullOfOrNull { it.albumArtUri }, tracks) }
        .sortedBy { it.title.lowercase() }

internal fun List<LocalTrack>.artists(): List<LocalGroup> =
    groupBy { it.artist }
        .map { (artist, tracks) -> LocalGroup(artist, "${tracks.size}", tracks.firstNotNullOfOrNull { it.albumArtUri }, tracks) }
        .sortedBy { it.title.lowercase() }

/**
 * Music files on this device: Songs / Albums / Artists. Synced lyrics come from LRCLIB in the
 * full player, just like for Spotify tracks. [onBack] is null when shown as a top-level tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesRoute(onBack: (() -> Unit)?, downloadsOnly: Boolean = false, viewModel: LocalFilesViewModel = hiltViewModel()) {
    viewModel.downloadsOnly = downloadsOnly
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onPermissionResult(it) }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionResult(true) else launcher.launch(permission)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openGroup by remember { mutableStateOf<LocalGroup?>(null) }
    BackHandler(enabled = openGroup != null) { openGroup = null }

    val tracks = (state as? LocalFilesUiState.Loaded)?.tracks.orEmpty()
    val downloadTasks by viewModel.downloads.collectAsStateWithLifecycle()
    // A song finished saving: show it in the list below.
    val savedCount = downloadTasks.count { it.status == com.spotineil.core.model.DownloadStatus.Done }
    LaunchedEffect(savedCount) { if (downloadsOnly && state is LocalFilesUiState.Loaded) viewModel.reload() }

    // Deleting a file the app does not own needs the user's OK; Android shows its own confirmation.
    var toDelete by remember { mutableStateOf<LocalTrack?>(null) }
    var pendingLegacyDelete by remember { mutableStateOf<Uri?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val legacy = pendingLegacyDelete
        pendingLegacyDelete = null
        if (result.resultCode == Activity.RESULT_OK) {
            // Android 10 only asks for access; the file is removed here. Android 11+ removes it itself.
            if (legacy != null) runCatching { context.contentResolver.delete(legacy, null, null) }
            openGroup = null
            viewModel.reload()
        }
    }
    fun askDelete(track: LocalTrack) { toDelete = track }
    fun deleteConfirmed(track: LocalTrack) {
        val uri = Uri.parse(track.contentUri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            try {
                context.contentResolver.delete(uri, null, null)
                openGroup = null
                viewModel.reload()
            } catch (e: RecoverableSecurityException) {
                pendingLegacyDelete = uri
                deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } catch (_: SecurityException) {
                // No access to this file.
            }
        }
    }
    toDelete?.let { track ->
        AlertDialog(
            containerColor = dialogContainer(),
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_body, track.title)) },
            confirmButton = {
                TextButton(onClick = { toDelete = null; deleteConfirmed(track) }) {
                    Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(openGroup?.title ?: stringResource(if (downloadsOnly) R.string.library_downloads else R.string.library_local_files)) },
                navigationIcon = {
                    val back = if (openGroup != null) ({ openGroup = null }) else onBack
                    if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                },
                actions = {
                    val list = openGroup?.tracks ?: tracks
                    if (list.isNotEmpty()) {
                        IconButton(onClick = { viewModel.play(list.shuffled(), 0) }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.library_shuffle)) }
                        IconButton(onClick = { viewModel.play(list, 0) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.library_play)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val s = state) {
                LocalFilesUiState.NeedsPermission -> MessageState(
                    Icons.Rounded.Lock,
                    stringResource(R.string.library_permission_title),
                    stringResource(R.string.library_permission_body),
                    actionLabel = stringResource(R.string.library_grant),
                    onAction = { launcher.launch(permission) },
                )
                LocalFilesUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LocalFilesUiState.Loaded -> when {
                    downloadsOnly && openGroup == null -> DownloadsPage(
                        tasks = downloadTasks,
                        tracks = s.tracks,
                        viewModel = viewModel,
                        onDelete = ::askDelete,
                    )
                    s.tracks.isEmpty() -> MessageState(Icons.Rounded.FolderOff, stringResource(if (downloadsOnly) R.string.library_no_downloads else R.string.library_no_local), null)
                    openGroup != null -> SongList(openGroup!!.tracks, ::askDelete) { i -> viewModel.play(openGroup!!.tracks, i) }
                    else -> Column {
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                            listOf(R.string.library_songs, R.string.library_albums, R.string.library_artists).forEachIndexed { i, label ->
                                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(stringResource(label)) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                            }
                        }
                        when (tab) {
                            0 -> SongList(s.tracks, ::askDelete) { i -> viewModel.play(s.tracks, i) }
                            1 -> GroupGrid(remember(s.tracks) { s.tracks.albums() }, circle = false) { openGroup = it }
                            else -> GroupGrid(remember(s.tracks) { s.tracks.artists() }, circle = true) { openGroup = it }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(tracks: List<LocalTrack>, onDelete: (LocalTrack) -> Unit, onPlay: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(
                t.title, "${t.artist} • ${formatDuration(t.durationMs)}", onClick = { onPlay(i) }, imageUrl = t.albumArtUri,
                onMore = { onDelete(t) }, moreContentDescription = stringResource(R.string.library_delete),
            )
        }
    }
}

@Composable
private fun GroupGrid(groups: List<LocalGroup>, circle: Boolean, onOpen: (LocalGroup) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalBottomContentPadding.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(groups, key = { it.title + "|" + it.subtitle }) { g ->
            MediaCard(
                title = g.title,
                subtitle = if (circle) stringResource(R.string.library_songs_count, g.tracks.size) else g.subtitle,
                imageUrl = g.artUri,
                onClick = { onOpen(g) },
                circle = circle,
            )
        }
    }
}

/** Downloads: the songs on their way (with progress and buttons), then the ones saved on the phone. */
@Composable
private fun DownloadsPage(
    tasks: List<com.spotineil.core.model.DownloadTask>,
    tracks: List<LocalTrack>,
    viewModel: LocalFilesViewModel,
    onDelete: (LocalTrack) -> Unit,
) {
    val context = LocalContext.current
    if (tasks.isEmpty() && tracks.isEmpty()) {
        MessageState(Icons.Rounded.FolderOff, stringResource(R.string.library_no_downloads), null)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
        if (tasks.isNotEmpty()) {
            item(key = "tasksHeader") {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.library_download_list),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (tasks.any { it.status == com.spotineil.core.model.DownloadStatus.Done }) {
                        TextButton(onClick = viewModel::clearFinishedDownloads) { Text(stringResource(R.string.library_download_clear)) }
                    }
                }
            }
            items(tasks.size, key = { "task:" + tasks[it].id }) { i ->
                val t = tasks[i]
                DownloadTaskRow(
                    t,
                    onPause = { viewModel.pauseDownload(t.id) },
                    onResume = { viewModel.resumeDownload(t.id) },
                    onRemove = { viewModel.removeDownload(t.id) },
                )
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "savedHeader") {
                Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.library_downloads_size, tracks.size, tracks.size, android.text.format.Formatter.formatShortFileSize(context, tracks.sumOf { it.sizeBytes })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(tracks, key = { _, t -> "file:" + t.id }) { i, t ->
                TrackRow(
                    t.title, "${t.artist} • ${formatDuration(t.durationMs)}", onClick = { viewModel.play(tracks, i) }, imageUrl = t.albumArtUri,
                    onMore = { onDelete(t) }, moreContentDescription = stringResource(R.string.library_delete),
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: com.spotineil.core.model.DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = task.status
    val percent = task.progress?.let { (it * 100).toInt() }
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    // "45% • 3.1 MB of 6.9 MB • 1.2 MB/s"
    val details = listOfNotNull(
        percent?.let { "$it%" },
        task.totalBytes?.let { stringResource(R.string.library_download_size, size(task.bytesDone), size(it)) },
        task.bytesPerSecond.takeIf { it > 0 && status == com.spotineil.core.model.DownloadStatus.Downloading }?.let { stringResource(R.string.library_download_speed, size(it)) },
    ).joinToString(" • ")
    val text = when (status) {
        com.spotineil.core.model.DownloadStatus.Queued -> stringResource(R.string.library_download_queued)
        com.spotineil.core.model.DownloadStatus.Downloading ->
            if (details.isNotEmpty()) details else stringResource(R.string.library_download_starting)
        com.spotineil.core.model.DownloadStatus.WaitingForNetwork -> stringResource(R.string.library_download_offline, percent ?: 0)
        com.spotineil.core.model.DownloadStatus.Paused -> stringResource(R.string.library_download_paused, percent ?: 0)
        com.spotineil.core.model.DownloadStatus.Converting -> stringResource(R.string.library_download_saving)
        com.spotineil.core.model.DownloadStatus.Done -> stringResource(R.string.library_download_done, task.fileName.orEmpty())
        com.spotineil.core.model.DownloadStatus.Failed -> stringResource(R.string.library_download_failed)
    }
    androidx.compose.foundation.layout.Row(
        Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("download_task_${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.spotineil.core.designsystem.component.CoverImage(task.artworkUrl, null, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    com.spotineil.core.model.DownloadStatus.Failed -> MaterialTheme.colorScheme.error
                    com.spotineil.core.model.DownloadStatus.Done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            when (status) {
                com.spotineil.core.model.DownloadStatus.Downloading, com.spotineil.core.model.DownloadStatus.Paused, com.spotineil.core.model.DownloadStatus.WaitingForNetwork -> {
                    val p = task.progress
                    if (p != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), drawStopIndicator = {})
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                com.spotineil.core.model.DownloadStatus.Converting -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                else -> Unit
            }
        }
        when (status) {
            com.spotineil.core.model.DownloadStatus.Queued, com.spotineil.core.model.DownloadStatus.Downloading, com.spotineil.core.model.DownloadStatus.WaitingForNetwork ->
                IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, stringResource(R.string.library_download_pause)) }
            com.spotineil.core.model.DownloadStatus.Paused ->
                IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.library_download_resume)) }
            com.spotineil.core.model.DownloadStatus.Failed ->
                IconButton(onClick = onResume) { Icon(Icons.Rounded.Refresh, stringResource(R.string.library_download_retry)) }
            else -> Unit
        }
        // Cancels at any point, also while a long song is being converted.
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Close,
                stringResource(if (status == com.spotineil.core.model.DownloadStatus.Done) R.string.library_download_hide else R.string.library_download_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
