@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spotineil.feature.discover

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SectionHeader
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import kotlinx.serialization.Serializable

@Serializable
data object DiscoverRoute

fun NavGraphBuilder.discoverScreen(onSettingsClick: () -> Unit, onMessage: (String) -> Unit) {
    composable<DiscoverRoute> { DiscoverRoute(onSettingsClick, onMessage) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DiscoverRoute(onSettingsClick: () -> Unit, onMessage: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettingsClick) { Icon(Icons.Rounded.Settings, stringResource(R.string.discover_settings)) }
        }
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf(R.string.discover_tab_free, R.string.discover_tab_radio).forEachIndexed { i, label ->
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
            0 -> FreeMusicTab(onMessage)
            1 -> RadioTab()
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun FreeMusicTab(onMessage: (String) -> Unit, viewModel: FreeMusicViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { onMessage(context.getString(it)) } }

    Column {
        DiscoverSearchField(query, viewModel::onQueryChange, stringResource(R.string.discover_free_hint))
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
            when (val s = state) {
                LoadState.Loading -> loadingRows()
                is LoadState.Failed -> item { ErrorState(s.error, onRetry = viewModel::retry) }
                is LoadState.Loaded -> {
                    val data = s.data
                    if (data.audius.isEmpty() && data.jamendo.isEmpty()) {
                        item { MessageState(Icons.Rounded.SearchOff, stringResource(R.string.discover_no_results), null) }
                    }
                    trackSection(
                        key = "audius",
                        title = if (query.isBlank()) R.string.discover_trending_audius else R.string.discover_on_audius,
                        tracks = data.audius,
                        nowPlaying = nowPlaying,
                        onPlay = viewModel::play,
                        onDownload = viewModel::download,
                    )
                    trackSection(
                        key = "jamendo",
                        title = if (query.isBlank()) R.string.discover_popular_jamendo else R.string.discover_on_jamendo,
                        tracks = data.jamendo,
                        nowPlaying = nowPlaying,
                        onPlay = viewModel::play,
                        onDownload = viewModel::download,
                    )
                    item { Footnote(stringResource(R.string.discover_free_footnote)) }
                }
            }
        }
    }
}

private fun LazyListScope.trackSection(
    key: String,
    title: Int,
    tracks: List<StreamTrack>,
    nowPlaying: String?,
    onPlay: (List<StreamTrack>, Int) -> Unit,
    onDownload: ((StreamTrack) -> Unit)?,
) {
    if (tracks.isEmpty()) return
    item(key = "h_$key") { SectionHeader(stringResource(title)) }
    itemsIndexed(tracks, key = { i, t -> "$key:${t.id}:$i" }) { i, t ->
        StreamTrackRow(
            track = t,
            isCurrent = nowPlaying == t.toPlayable().mediaId,
            onClick = { onPlay(tracks, i) },
            onDownload = if (t.canDownload && onDownload != null) ({ onDownload(t) }) else null,
        )
    }
}

@Composable
fun StreamTrackRow(track: StreamTrack, isCurrent: Boolean, onClick: () -> Unit, onDownload: (() -> Unit)?, onLongClick: (() -> Unit)? = null) {
    val withDownload = com.spotineil.core.ui.LocalSongMenuWithDownload.current
    val plain = com.spotineil.core.ui.LocalSongMenu.current
    // The ⋮ menu (and a long press) offers Download when the artist or licence allows it.
    val longClick = onLongClick ?: when {
        onDownload != null && withDownload != null -> ({ withDownload(track.toPlayable(), onDownload) })
        plain != null -> ({ plain(track.toPlayable()) })
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = longClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("stream_${track.source.name}_${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CoverImage(track.artworkUrl, null, Modifier.size(48.dp))
            if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                track.artist.takeIf { it.isNotBlank() },
                track.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
            ).joinToString(" • ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        com.spotineil.core.ui.SongMenuButton(longClick)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun RadioTab(viewModel: RadioViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlayingId.collectAsStateWithLifecycle()
    val favoriteIds = favorites.map { it.id }.toSet()

    val row: @Composable (RadioStation) -> Unit = { st ->
        StationRow(
            station = st,
            isFavorite = st.id in favoriteIds,
            isCurrent = nowPlaying == st.toPlayable().mediaId,
            onClick = { viewModel.play(st) },
            onToggleFavorite = { viewModel.toggleFavorite(st) },
        )
    }

    Column {
        DiscoverSearchField(query, viewModel::onQueryChange, stringResource(R.string.discover_radio_hint))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RadioViewModel.TAGS, key = { it }) { t ->
                FilterChip(
                    selected = tag == t,
                    onClick = { viewModel.selectTag(if (tag == t) null else t) },
                    label = { Text(if (t == "opm") "OPM" else t.replaceFirstChar { it.uppercase() }) },
                    shape = CircleShape,
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp)) {
            val browsing = query.isBlank() && tag == null
            if (browsing && favorites.isNotEmpty()) {
                item(key = "favH") { SectionHeader(stringResource(R.string.discover_radio_favorites)) }
                items(favorites, key = { "fav:" + it.id }) { row(it) }
            }
            when (val s = state) {
                LoadState.Loading -> loadingRows()
                is LoadState.Failed -> item { ErrorState(s.error, onRetry = viewModel::retry) }
                is LoadState.Loaded -> {
                    val results = s.data.results
                    if (results != null) {
                        if (results.isEmpty()) item { MessageState(Icons.Rounded.Radio, stringResource(R.string.discover_no_stations), null) }
                        items(results, key = { "r:" + it.id }) { row(it) }
                    } else {
                        if (s.data.local.isNotEmpty()) {
                            item(key = "localH") { SectionHeader(stringResource(R.string.discover_radio_local, viewModel.countryName)) }
                            items(s.data.local, key = { "l:" + it.id }) { row(it) }
                        }
                        item(key = "topH") { SectionHeader(stringResource(R.string.discover_radio_top)) }
                        items(s.data.top, key = { "t:" + it.id }) { row(it) }
                    }
                    item { Footnote(stringResource(R.string.discover_radio_footnote)) }
                }
            }
        }
    }
}

@Composable
internal fun StationRow(station: RadioStation, isFavorite: Boolean, isCurrent: Boolean, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CoverImage(station.faviconUrl, null, Modifier.size(48.dp), placeholderIcon = Icons.Rounded.Radio)
            if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(station.subtitle.takeIf { it.isNotBlank() }, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                stringResource(if (isFavorite) R.string.discover_unfavorite else R.string.discover_favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------

private fun LazyListScope.loadingRows() = items(8, key = { "sk$it" }) { SkeletonTrackRow() }

@Composable
private fun DiscoverSearchField(value: String, onChange: (String) -> Unit, hint: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.discover_clear)) }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
