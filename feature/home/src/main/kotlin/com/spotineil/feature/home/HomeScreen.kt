package com.spotineil.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotineil.core.designsystem.component.CarouselSection
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MediaCard
import com.spotineil.core.designsystem.component.SkeletonCarousel
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.HomeFeed
import com.spotineil.core.model.Image
import com.spotineil.core.model.Show
import com.spotineil.core.ui.FreeTierNotice

/** Filter chips under the greeting. */
enum class HomeFilter(val label: Int) {
    All(R.string.home_filter_all),
    Music(R.string.home_filter_music),
    Podcasts(R.string.home_filter_podcasts),
}

/** One tile of the 2-column quick-access grid. */
private sealed interface QuickPick {
    val key: String
    data object LikedSongs : QuickPick { override val key = "liked" }
    data class Item(override val key: String, val title: String, val images: List<Image>, val onClick: () -> Unit) : QuickPick
}

@Composable
fun HomeRoute(
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onLikedSongsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onPlayTrack = viewModel::play,
        onPlayContext = viewModel::playContext,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onLikedSongsClick = onLikedSongsClick,
        onProfileClick = onProfileClick,
        onSettingsClick = onSettingsClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onPlayContext: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onLikedSongsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    val music = filter != HomeFilter.Podcasts
    val podcasts = filter != HomeFilter.Music

    PullToRefreshBox(
        isRefreshing = state.isRefreshing && state.feed != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            item(key = "header") { HomeHeader(state, onProfileClick, onSettingsClick) }
            item(key = "filters") { FilterChips(filter) { filter = it } }
            if (state.showFreeNotice) item(key = "free") { FreeTierNotice() }

            val feed = state.feed
            when {
                feed != null -> {
                    if (music) {
                        item(key = "quick") {
                            val picks = remember(feed) { quickPicks(feed, onPlaylistClick, onAlbumClick, onArtistClick) }
                            QuickPicks(picks, onLikedSongsClick)
                        }
                    }
                    if (podcasts && feed.shows.isNotEmpty()) {
                        item(key = "shows") { PodcastSection(feed.shows, onPlayContext) }
                    }
                    if (music) {
                        item(key = "recent") {
                            CarouselSection(stringResource(R.string.home_recently_played), feed.recentlyPlayed, key = { it.id }) {
                                MediaCard(it.name, it.artistNames, rememberImageUrl(it.images, 300), { onPlayTrack(it.uri) })
                            }
                        }
                        item(key = "topArtists") {
                            CarouselSection(stringResource(R.string.home_top_artists), feed.topArtists, key = { it.id }) {
                                MediaCard(it.name, null, rememberImageUrl(it.images, 300), { onArtistClick(it.id) }, circle = true)
                            }
                        }
                        item(key = "topTracks") {
                            CarouselSection(stringResource(R.string.home_top_tracks), feed.topTracks, key = { it.id }) {
                                MediaCard(it.name, it.artistNames, rememberImageUrl(it.images, 300), { onPlayTrack(it.uri) })
                            }
                        }
                        item(key = "playlists") {
                            CarouselSection(stringResource(R.string.home_your_playlists), feed.playlists, key = { it.id }) {
                                MediaCard(it.name, it.owner.displayName, rememberImageUrl(it.images, 300), { onPlaylistClick(it.id) })
                            }
                        }
                        item(key = "newReleases") {
                            CarouselSection(stringResource(R.string.home_new_releases), feed.newReleases, key = { it.id }) {
                                MediaCard(it.name, it.artists.joinToString { a -> a.name }, rememberImageUrl(it.images, 300), { onAlbumClick(it.id) })
                            }
                        }
                        item(key = "savedAlbums") {
                            CarouselSection(stringResource(R.string.home_saved_albums), feed.savedAlbums, key = { it.id }) {
                                MediaCard(it.name, it.artists.joinToString { a -> a.name }, rememberImageUrl(it.images, 300), { onAlbumClick(it.id) })
                            }
                        }
                    }
                    val emptyText = when {
                        filter == HomeFilter.Podcasts && feed.shows.isEmpty() -> R.string.home_no_podcasts
                        feed.isEmpty -> R.string.home_empty
                        else -> null
                    }
                    if (emptyText != null) {
                        item(key = "empty") {
                            Text(stringResource(emptyText), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error != null -> item(key = "error") { ErrorState(state.error, onRetry = onRefresh) }
                else -> items(4) { SkeletonCarousel(Modifier.padding(top = 20.dp), circle = it == 1) }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, onProfileClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (state.greeting) {
                    Greeting.Morning -> R.string.home_good_morning
                    Greeting.Afternoon -> R.string.home_good_afternoon
                    Greeting.Evening -> R.string.home_good_evening
                    Greeting.Night -> R.string.home_good_night
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettingsClick) { Icon(Icons.Rounded.Settings, stringResource(R.string.home_settings)) }
        Spacer(Modifier.width(8.dp))
        Avatar(state, onProfileClick)
    }
}

/** Profile photo, or the first letter of the display name on the accent color. */
@Composable
private fun Avatar(state: HomeUiState, onClick: () -> Unit) {
    val user = state.user
    val url = user?.images?.let { rememberImageUrl(it, 128) }
    val description = stringResource(R.string.home_profile)
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            CoverImage(url, description, Modifier.fillMaxSize(), shape = CircleShape)
        } else {
            Text(
                user?.displayName?.trim()?.firstOrNull()?.uppercase() ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun FilterChips(selected: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(HomeFilter.entries, key = { it.name }) { f ->
            val isSelected = f == selected
            Text(
                stringResource(f.label),
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

/** Liked Songs first, then playlists, saved albums and top artists — up to 8 tiles. */
private fun quickPicks(
    feed: HomeFeed,
    onPlaylistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
): List<QuickPick> {
    val items = feed.playlists.map { p -> QuickPick.Item("p:${p.id}", p.name, p.images) { onPlaylistClick(p.id) } } +
        feed.savedAlbums.map { a -> QuickPick.Item("a:${a.id}", a.name, a.images) { onAlbumClick(a.id) } } +
        feed.topArtists.map { a -> QuickPick.Item("r:${a.id}", a.name, a.images) { onArtistClick(a.id) } }
    return listOf(QuickPick.LikedSongs) + items.distinctBy { it.key }.take(7)
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun QuickPicks(picks: List<QuickPick>, onLikedSongsClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        picks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { pick ->
                    Row(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(TileShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = if (pick is QuickPick.Item) pick.onClick else onLikedSongsClick),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (pick) {
                            QuickPick.LikedSongs -> LikedSongsArt()
                            is QuickPick.Item -> CoverImage(
                                rememberImageUrl(pick.images, 160),
                                null,
                                Modifier.size(64.dp),
                                shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (pick is QuickPick.Item) pick.title else stringResource(R.string.home_liked_songs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LikedSongsArt() {
    Box(
        Modifier
            .size(64.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF4A1FE0), Color(0xFF8E7CF0), Color(0xFFB6E3D4)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** Big square podcast cards with the title over a dark gradient. */
@Composable
private fun PodcastSection(shows: List<Show>, onPlay: (String) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            stringResource(R.string.home_your_podcasts),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shows, key = { it.id }) { show ->
                Box(
                    Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onPlay(show.uri) },
                ) {
                    CoverImage(rememberImageUrl(show.images, 640), show.name, Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.45f)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(
                            show.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            show.publisher,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
