package com.spotineil.feature.discover.free

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotineil.core.common.ApplicationScope
import com.spotineil.core.data.repository.DownloadRepository
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.RecentItem
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import com.spotineil.core.model.YOUTUBE_MEDIA_PREFIX
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.model.isYouTube
import com.spotineil.core.model.toYouTubeVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.spotineil.core.player.PlaybackController
import com.spotineil.feature.discover.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts playback of free sources and remembers what was played ("Jump back in").
 * Shared by every screen of the no-account app.
 */
@Singleton
class FreePlayer @Inject constructor(
    private val player: PlaybackController,
    private val recents: RecentsRepository,
    private val radio: RadioRepository,
    private val downloads: DownloadRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Media id of what's playing, to highlight the current row. */
    val nowPlayingId: Flow<String?> = player.state.map { it.track?.uri }.distinctUntilChanged()

    fun playTracks(tracks: List<StreamTrack>, index: Int) = playMedia(tracks.map { it.toPlayable() }, index)

    /** Plays on-device media; YouTube items in the list are skipped (they play in YouTube's player). */
    fun playMedia(items: List<PlayableMedia>, index: Int) {
        val target = items.getOrNull(index)
        val playable = items.filterNot { it.isYouTube }
        if (playable.isEmpty()) return
        val start = playable.indexOf(target).coerceAtLeast(0)
        player.playMedia(playable, start)
        scope.launch { recents.record(RecentItem.Song(playable[start])) }
    }

    // ---------------- YouTube: queue for the official embedded player ----------------

    private val _youTubeQueue = MutableStateFlow<List<YouTubeVideo>>(emptyList())
    val youTubeQueue: StateFlow<List<YouTubeVideo>> = _youTubeQueue.asStateFlow()
    private val _youTubeIndex = MutableStateFlow(0)
    val youTubeIndex: StateFlow<Int> = _youTubeIndex.asStateFlow()

    /** Sets the YouTube queue and pauses on-device playback; then navigate to the YouTube player. */
    fun playYouTube(videos: List<YouTubeVideo>, index: Int) {
        if (videos.isEmpty()) return
        _youTubeQueue.value = videos
        selectYouTube(index)
        player.pause()
    }

    fun selectYouTube(index: Int) {
        val list = _youTubeQueue.value
        if (list.isEmpty()) return
        val i = index.coerceIn(0, list.lastIndex)
        _youTubeIndex.value = i
        scope.launch { recents.record(RecentItem.Song(list[i].toPlayable())) }
    }

    /**
     * Liked Songs / history can mix YouTube and on-device songs. Returns true when the tapped item
     * is a YouTube song (the caller then opens the YouTube player).
     */
    fun playMixed(items: List<PlayableMedia>, index: Int): Boolean {
        val target = items.getOrNull(index) ?: return false
        if (!target.isYouTube) {
            playMedia(items, index)
            return false
        }
        val videos = items.mapNotNull { it.toYouTubeVideo() }
        playYouTube(videos, videos.indexOfFirst { YOUTUBE_MEDIA_PREFIX + it.id == target.mediaId }.coerceAtLeast(0))
        return true
    }

    fun playStation(station: RadioStation) {
        player.playMedia(listOf(station.toPlayable()), 0)
        scope.launch {
            recents.record(RecentItem.Station(station))
            radio.reportPlay(station)
        }
    }

    fun rememberPodcast(podcast: Podcast) = scope.launch { recents.record(RecentItem.Show(podcast)) }
    fun rememberCollection(collection: ArchiveCollection) = scope.launch { recents.record(RecentItem.Collection(collection)) }

    /** False when the track can't be downloaded (not allowed by the artist/licence). */
    fun download(track: StreamTrack): Boolean = downloads.download(track)
}

// ---------------------------------------------------------------------------------------------
// Spotui-style building blocks
// ---------------------------------------------------------------------------------------------

/** Round profile button (opens Settings) — there's no account, so it shows a person icon. */
@Composable
internal fun ProfileButton(onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Person, stringResource(R.string.free_settings), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
}

/** Pill-shaped filter chips; the selected one uses the accent color. */
@Composable
internal fun <T> FilterPills(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) item(key = "leading") { leading() }
        items(options, key = { it.toString() }) { option ->
            val isSelected = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** One cell of the 2-column quick-access grid at the top of Home. */
@Composable
internal fun QuickTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, art: @Composable () -> Unit) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) { art() }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
internal fun QuickTileGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The purple "Liked Songs" artwork. */
@Composable
internal fun LikedArt(modifier: Modifier = Modifier.fillMaxSize(), iconSize: Dp = 24.dp) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** A shelf title + horizontally scrolling row (hidden when [items] is empty). */
@Composable
internal fun <T> Shelf(title: String, items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = key) { content(it) }
        }
    }
}

/** Big square card: cover, bold title, grey subtitle; optional badge (LIVE / PREVIEW). */
@Composable
internal fun BigCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    circle: Boolean = false,
    badge: String? = null,
    isPlaying: Boolean = false,
    placeholderIcon: ImageVector? = null,
) {
    Column(modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size)) {
            val shape = if (circle) CircleShape else RoundedCornerShape(4.dp)
            if (placeholderIcon != null) {
                CoverImage(imageUrl, title, Modifier.fillMaxSize(), shape = shape, placeholderIcon = placeholderIcon)
            } else {
                CoverImage(imageUrl, title, Modifier.fillMaxSize(), shape = shape)
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (isPlaying) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A row in Your Library: artwork, title, "Type • detail". */
@Composable
internal fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit, circle: Boolean = false, art: @Composable (Modifier) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(64.dp).clip(if (circle) CircleShape else RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ScreenTitle(title: String, onProfileClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileButton(onProfileClick)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}
