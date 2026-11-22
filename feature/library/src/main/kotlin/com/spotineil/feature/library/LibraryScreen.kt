package com.spotineil.feature.library

import com.spotineil.core.designsystem.theme.menuContainer
import com.spotineil.core.designsystem.theme.dialogContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MediaCard
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.errorMessage
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.LibraryItem
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.LibrarySort

data class LibraryCallbacks(
    val onPlaylistClick: (String) -> Unit,
    val onAlbumClick: (String) -> Unit,
    val onArtistClick: (String) -> Unit,
    val onLikedSongsClick: () -> Unit,
    val onLocalFilesClick: () -> Unit,
    val onMessage: (String) -> Unit,
)

@Composable
fun LibraryRoute(callbacks: LibraryCallbacks, viewModel: LibraryViewModel = hiltViewModel()) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val likedCount by viewModel.likedCount.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var errorToShow by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(Unit) { viewModel.created.collect { callbacks.onPlaylistClick(it) } }
    LaunchedEffect(Unit) { viewModel.error.collect { errorToShow = it } }
    errorToShow?.let { val msg = errorMessage(it); LaunchedEffect(it) { callbacks.onMessage(msg); errorToShow = null } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showCreate = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.library_create_playlist)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryItemType.entries.toList(), key = { it.name }) { type ->
                FilterChip(
                    selected = tab == type,
                    onClick = { viewModel.selectTab(type) },
                    label = { Text(stringResource(type.label())) },
                    shape = CircleShape,
                )
            }
        }
        SortBar(
            sort = settings.librarySort,
            grid = settings.libraryGrid,
            onSort = { viewModel.setSort(it) },
            onToggleGrid = { viewModel.setGrid(!settings.libraryGrid) },
        )
        LibraryList(
            items = items,
            grid = settings.libraryGrid,
            header = if (tab == LibraryItemType.Playlist) {
                { PinnedEntries(likedCount, callbacks.onLikedSongsClick, callbacks.onLocalFilesClick) }
            } else null,
            onClick = { item ->
                when (item.type) {
                    LibraryItemType.Playlist -> callbacks.onPlaylistClick(item.id)
                    LibraryItemType.Album -> callbacks.onAlbumClick(item.id)
                    LibraryItemType.Artist -> callbacks.onArtistClick(item.id)
                    LibraryItemType.Show -> viewModel.playShow(item.uri)
                }
            },
        )
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, desc, public, collab ->
                showCreate = false
                viewModel.createPlaylist(name, desc, public, collab)
            },
        )
    }
}

private fun LibraryItemType.label() = when (this) {
    LibraryItemType.Playlist -> R.string.library_playlists
    LibraryItemType.Album -> R.string.library_albums
    LibraryItemType.Artist -> R.string.library_artists
    LibraryItemType.Show -> R.string.library_podcasts
}

@Composable
private fun SortBar(sort: LibrarySort, grid: Boolean, onSort: (LibrarySort) -> Unit, onToggleGrid: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(sort.label()))
            }
            DropdownMenu(containerColor = menuContainer(), expanded = menu, onDismissRequest = { menu = false }) {
                LibrarySort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(stringResource(s.label())) }, onClick = { onSort(s); menu = false })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleGrid) {
            Icon(
                if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                stringResource(if (grid) R.string.library_list_view else R.string.library_grid_view),
            )
        }
    }
}

private fun LibrarySort.label() = when (this) {
    LibrarySort.Recent -> R.string.library_sort_recent
    LibrarySort.Alphabetical -> R.string.library_sort_az
    LibrarySort.Creator -> R.string.library_sort_creator
}

@Composable
private fun PinnedEntries(likedCount: Int?, onLiked: () -> Unit, onLocal: () -> Unit) {
    Column {
        PinnedRow(
            title = stringResource(R.string.library_liked_songs),
            subtitle = likedCount?.let { stringResource(R.string.library_songs_count, it) } ?: stringResource(R.string.library_playlist),
            brush = Brush.linearGradient(listOf(Color(0xFF4A2FBD), Color(0xFF8BA9E8))),
            icon = { Icon(Icons.Rounded.Favorite, null, tint = Color.White) },
            onClick = onLiked,
        )
        PinnedRow(
            title = stringResource(R.string.library_local_files),
            subtitle = stringResource(R.string.library_local_files_sub),
            brush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))),
            icon = { Icon(Icons.Rounded.FolderOpen, null, tint = Color.Black) },
            onClick = onLocal,
        )
    }
}

@Composable
private fun PinnedRow(title: String, subtitle: String, brush: Brush, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(brush), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryList(
    items: LazyPagingItems<LibraryItem>,
    grid: Boolean,
    header: (@Composable () -> Unit)?,
    onClick: (LibraryItem) -> Unit,
) {
    val bottom = LocalBottomContentPadding.current + 16.dp
    val refresh = items.loadState.refresh
    val isEmpty = items.itemCount == 0
    val emptyContent: @Composable () -> Unit = {
        when {
            refresh is LoadState.Loading -> Column { repeat(6) { SkeletonTrackRow() } }
            refresh is LoadState.Error -> ErrorState(refresh.error, onRetry = { items.retry() })
            else -> MessageState(Icons.Rounded.LibraryMusic, stringResource(R.string.library_empty), null)
        }
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            if (isEmpty) item(span = { GridItemSpan(maxLineSpan) }) { emptyContent() }
            items(items.itemCount, key = { i -> items.peek(i)?.let { "${it.type}:${it.id}" } ?: i }) { i ->
                items[i]?.let { item ->
                    MediaCard(item.name, item.subtitle, item.imageUrl, { onClick(item) }, circle = item.type == LibraryItemType.Artist)
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
            if (header != null) item { header() }
            if (isEmpty) item { emptyContent() }
            items(items.itemCount, key = { i -> items.peek(i)?.let { "${it.type}:${it.id}" } ?: i }) { i ->
                items[i]?.let { item -> LibraryRow(item) { onClick(item) } }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            item.imageUrl, null, Modifier.size(56.dp),
            shape = if (item.type == LibraryItemType.Artist) CircleShape else MaterialTheme.shapes.small,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String, String?, Boolean, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var public by rememberSaveable { mutableStateOf(false) }
    var collaborative by rememberSaveable { mutableStateOf(false) }
    AlertDialog(containerColor = dialogContainer(), 
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_create_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.library_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.library_description)) })
                ToggleRow(stringResource(R.string.library_public), public && !collaborative, enabled = !collaborative) { public = it }
                ToggleRow(stringResource(R.string.library_collaborative), collaborative) { collaborative = it; if (it) public = false }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, description, public, collaborative) }) {
                Text(stringResource(R.string.library_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
