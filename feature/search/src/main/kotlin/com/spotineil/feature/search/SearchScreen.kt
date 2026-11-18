package com.spotineil.feature.search

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SectionHeader
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.Category
import com.spotineil.core.model.RecentSearch
import com.spotineil.core.model.SearchResult
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SearchType
import com.spotineil.core.ui.TrackActionTarget
import com.spotineil.core.ui.TrackActionsSheet
import com.spotineil.core.ui.toActionTarget

data class SearchCallbacks(
    val onArtistClick: (String) -> Unit,
    val onAlbumClick: (String) -> Unit,
    val onPlaylistClick: (String) -> Unit,
    val onMessage: (String) -> Unit,
)

@Composable
fun SearchRoute(callbacks: SearchCallbacks, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val paged = viewModel.pagedResults.collectAsLazyPagingItems()
    SearchScreen(
        query = query,
        filter = filter,
        state = state,
        paged = paged,
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
        onSubmit = { viewModel.commitQuery() },
        onRetry = viewModel::retry,
        onRemoveRecent = { viewModel.removeRecent(it) },
        onClearRecents = { viewModel.clearRecents() },
        onPlay = viewModel::play,
        onPlayContext = viewModel::playContext,
        onOpen = { viewModel.commitQuery() },
        callbacks = callbacks,
    )
}

@Composable
fun SearchScreen(
    query: String,
    filter: SearchType?,
    state: SearchUiState,
    paged: LazyPagingItems<SearchResult>?,
    onQueryChange: (String) -> Unit,
    onFilterChange: (SearchType?) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onPlay: (String) -> Unit,
    onPlayContext: (String) -> Unit,
    onOpen: () -> Unit,
    callbacks: SearchCallbacks,
) {
    var actionTarget by remember { mutableStateOf<TrackActionTarget?>(null) }
    val focus = LocalFocusManager.current
    val open: (() -> Unit) -> Unit = { action -> onOpen(); focus.clearFocus(); action() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(); focus.clearFocus() }),
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search_field"),
        )
        if (query.isNotBlank()) {
            FilterChips(filter, onFilterChange)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            when {
                state is SearchUiState.Idle -> idleContent(state.recents, state.categories, onQueryChange, onRemoveRecent, onClearRecents)
                state is SearchUiState.Loading -> items(8) { SkeletonTrackRow() }
                state is SearchUiState.Error -> item { ErrorState(state.error, onRetry) }
                state is SearchUiState.Empty -> item {
                    MessageState(Icons.Rounded.SearchOff, stringResource(R.string.search_no_results, state.query), null)
                }
                state is SearchUiState.Results && filter == null -> topResults(
                    state.results,
                    onTrackClick = { open { onPlay(it) } },
                    onTrackMore = { actionTarget = it },
                    onArtistClick = { open { callbacks.onArtistClick(it) } },
                    onAlbumClick = { open { callbacks.onAlbumClick(it) } },
                    onPlaylistClick = { open { callbacks.onPlaylistClick(it) } },
                    onShowClick = { open { onPlayContext(it) } },
                    onSeeAll = onFilterChange,
                )
                state is SearchUiState.Results && paged != null -> pagedContent(
                    paged,
                    onTrackClick = { open { onPlay(it) } },
                    onTrackMore = { actionTarget = it },
                    onArtistClick = { open { callbacks.onArtistClick(it) } },
                    onAlbumClick = { open { callbacks.onAlbumClick(it) } },
                    onPlaylistClick = { open { callbacks.onPlaylistClick(it) } },
                    onShowClick = { open { onPlayContext(it) } },
                )
            }
        }
    }

    actionTarget?.let { target ->
        TrackActionsSheet(
            target = target,
            onDismiss = { actionTarget = null },
            onGoToArtist = callbacks.onArtistClick,
            onGoToAlbum = callbacks.onAlbumClick,
            onMessage = callbacks.onMessage,
        )
    }
}

@Composable
private fun FilterChips(selected: SearchType?, onSelect: (SearchType?) -> Unit) {
    val options = listOf(
        null to R.string.search_filter_all,
        SearchType.Track to R.string.search_filter_songs,
        SearchType.Artist to R.string.search_filter_artists,
        SearchType.Album to R.string.search_filter_albums,
        SearchType.Playlist to R.string.search_filter_playlists,
        SearchType.Show to R.string.search_filter_podcasts,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.second }) { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(label)) },
                shape = CircleShape,
                modifier = Modifier.testTag("chip_${type?.name ?: "All"}"),
            )
        }
    }
}

private fun LazyListScope.idleContent(
    recents: List<RecentSearch>,
    categories: List<Category>,
    onQuery: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recents.isNotEmpty()) {
        item(key = "recentHeader") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_all)) }
            }
        }
        items(recents, key = { "recent:" + it.query }) { recent ->
            Row(
                Modifier.fillMaxWidth().clickable { onQuery(recent.query) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(recent.query, Modifier.weight(1f))
                IconButton(onClick = { onRemove(recent.query) }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.search_remove_recent))
                }
            }
        }
    }
    if (categories.isNotEmpty()) {
        item(key = "browseHeader") { SectionHeader(stringResource(R.string.search_browse_all)) }
        categories.chunked(2).forEachIndexed { rowIndex, row ->
            item(key = "cat$rowIndex") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { i, cat -> CategoryTile(cat, rowIndex * 2 + i, Modifier.weight(1f)) { onQuery(cat.name) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private val tileColors = listOf(
    0xFF8D67AB, 0xFFE8115B, 0xFF1E3264, 0xFF148A08, 0xFFBC5900, 0xFF509BF5, 0xFFAF2896, 0xFF27856A,
)

@Composable
private fun CategoryTile(category: Category, index: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color(tileColors[index % tileColors.size]))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(category.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        CoverImage(
            rememberImageUrl(category.icons, 160),
            null,
            Modifier.size(56.dp).align(Alignment.BottomEnd),
            shape = MaterialTheme.shapes.small,
        )
    }
}

private fun LazyListScope.topResults(
    results: SearchResults,
    onTrackClick: (String) -> Unit,
    onTrackMore: (TrackActionTarget) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onShowClick: (String) -> Unit,
    onSeeAll: (SearchType) -> Unit,
) {
    fun header(key: String, title: Int, type: SearchType) = item(key = key) {
        SectionHeader(stringResource(title), actionLabel = stringResource(R.string.search_see_all), onAction = { onSeeAll(type) })
    }
    if (results.tracks.isNotEmpty()) {
        header("hTracks", R.string.search_filter_songs, SearchType.Track)
        items(results.tracks.take(5), key = { "t" + it.id }) { t ->
            TrackRow(
                t.name, t.artistNames, onClick = { onTrackClick(t.uri) },
                imageUrl = rememberImageUrl(t.images, 120), explicit = t.explicit,
                onMore = { onTrackMore(t.toActionTarget()) },
                modifier = Modifier.testTag("track_${t.id}"),
            )
        }
    }
    if (results.artists.isNotEmpty()) {
        header("hArtists", R.string.search_filter_artists, SearchType.Artist)
        items(results.artists.take(4), key = { "a" + it.id }) { a ->
            ResultRow(a.name, stringResource(R.string.search_type_artist), rememberImageUrl(a.images, 120), circle = true) { onArtistClick(a.id) }
        }
    }
    if (results.albums.isNotEmpty()) {
        header("hAlbums", R.string.search_filter_albums, SearchType.Album)
        items(results.albums.take(4), key = { "al" + it.id }) { a ->
            ResultRow(a.name, a.artists.joinToString { it.name }, rememberImageUrl(a.images, 120)) { onAlbumClick(a.id) }
        }
    }
    if (results.playlists.isNotEmpty()) {
        header("hPlaylists", R.string.search_filter_playlists, SearchType.Playlist)
        items(results.playlists.take(4), key = { "p" + it.id }) { p ->
            ResultRow(p.name, p.owner.displayName.orEmpty(), rememberImageUrl(p.images, 120)) { onPlaylistClick(p.id) }
        }
    }
    if (results.shows.isNotEmpty()) {
        header("hShows", R.string.search_filter_podcasts, SearchType.Show)
        items(results.shows.take(4), key = { "s" + it.id }) { s ->
            ResultRow(s.name, s.publisher, rememberImageUrl(s.images, 120)) { onShowClick(s.uri) }
        }
    }
}

private fun LazyListScope.pagedContent(
    paged: LazyPagingItems<SearchResult>,
    onTrackClick: (String) -> Unit,
    onTrackMore: (TrackActionTarget) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onShowClick: (String) -> Unit,
) {
    val refresh = paged.loadState.refresh
    if (refresh is LoadState.Loading && paged.itemCount == 0) {
        items(8) { SkeletonTrackRow() }
        return
    }
    if (refresh is LoadState.Error && paged.itemCount == 0) {
        item { ErrorState(refresh.error, onRetry = { paged.retry() }) }
        return
    }
    items(paged.itemCount, key = { i -> paged.peek(i)?.let { "${it::class.simpleName}:${it.id}:$i" } ?: i }) { i ->
        when (val r = paged[i]) {
            is SearchResult.TrackResult -> TrackRow(
                r.track.name, r.track.artistNames, onClick = { onTrackClick(r.track.uri) },
                imageUrl = rememberImageUrl(r.track.images, 120), explicit = r.track.explicit,
                onMore = { onTrackMore(r.track.toActionTarget()) },
            )
            is SearchResult.ArtistResult -> ResultRow(r.artist.name, stringResource(R.string.search_type_artist), rememberImageUrl(r.artist.images, 120), circle = true) { onArtistClick(r.artist.id) }
            is SearchResult.AlbumResult -> ResultRow(r.album.name, r.album.artists.joinToString { it.name }, rememberImageUrl(r.album.images, 120)) { onAlbumClick(r.album.id) }
            is SearchResult.PlaylistResult -> ResultRow(r.playlist.name, r.playlist.owner.displayName.orEmpty(), rememberImageUrl(r.playlist.images, 120)) { onPlaylistClick(r.playlist.id) }
            is SearchResult.ShowResult -> ResultRow(r.show.name, r.show.publisher, rememberImageUrl(r.show.images, 120)) { onShowClick(r.show.uri) }
            null -> SkeletonTrackRow()
        }
    }
    if (paged.loadState.append is LoadState.Loading) {
        item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
}

@Composable
private fun ResultRow(title: String, subtitle: String, imageUrl: String?, circle: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(imageUrl, null, Modifier.size(48.dp), shape = if (circle) CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
