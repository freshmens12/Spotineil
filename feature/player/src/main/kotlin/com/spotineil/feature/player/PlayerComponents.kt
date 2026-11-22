package com.spotineil.feature.player

import com.spotineil.core.designsystem.theme.LocalGlass
import com.spotineil.core.designsystem.theme.glassPanel
import com.spotineil.core.designsystem.theme.opaqueBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.model.RepeatMode
import com.spotineil.core.ui.FreeTierNotice

data class PlayerActions(
    val onTogglePlay: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onLike: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    val onExpand: () -> Unit = {},
    val onShowQueue: () -> Unit = {},
    val onShowDevices: () -> Unit = {},
    val onShowSleepTimer: () -> Unit = {},
    val onToggleLyrics: () -> Unit = {},
    val onAddToPlaylist: (() -> Unit)? = null,
    val onArtistClick: () -> Unit = {},
    val onAlbumClick: () -> Unit = {},
)

/** Docked above the bottom navigation. Tap or swipe up to expand. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val dominant by rememberDominantColor(track.imageUrl, surface)
    // A deep, artwork-tinted bar (like the screenshot): dark enough for white text on any cover.
    val tint = lerp(dominant.copy(alpha = 1f), Color.Black, 0.45f)
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(if (glass) 20.dp else 8.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(60.dp)
            // Soft glass: a frosted panel; otherwise the artwork-tinted bar.
            .then(if (glass) Modifier.glassPanel(shape, fill = 0.14f, scrim = 0.94f) else Modifier.clip(shape).background(tint))
            .clickable(onClick = actions.onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -12) actions.onExpand() }
            }
            .testTag("mini_player"),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(track.imageUrl, null, artworkModifier.size(44.dp), shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier,
                )
                Text(
                    listOfNotNull(track.artists, state.playback.deviceName).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.canLike) {
                IconButton(onClick = actions.onLike, modifier = Modifier.testTag("mini_like")) {
                    val liked = state.isLiked == true
                    Icon(
                        if (liked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                        stringResource(if (liked) R.string.player_unlike else R.string.player_like),
                        tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            IconButton(onClick = actions.onTogglePlay, modifier = Modifier.testTag("mini_play_pause")) {
                Icon(
                    if (state.playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (state.playback.isPlaying) R.string.player_pause else R.string.player_play),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp).height(2.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.22f),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

@Composable
private fun LikeIcon(liked: Boolean) {
    Icon(
        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        stringResource(if (liked) R.string.player_unlike else R.string.player_like),
        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

/** Stateless full-screen player; shared-element modifiers are injected by the host. */
@Composable
fun FullPlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    showLyrics: Boolean,
    lyricsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    val track = state.track
    val background = opaqueBackground()
    val dominant by rememberDominantColor(track?.imageUrl, MaterialTheme.colorScheme.surfaceContainerHigh)

    Box(
        modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 24) actions.onCollapse() }
            }
            .testTag("full_player"),
    ) {
        // Blurred artwork backdrop (blur is applied on Android 12+; older versions show the gradient only).
        CoverImage(
            track?.imageUrl, null,
            Modifier.fillMaxSize().blur(80.dp).alpha(0.35f),
            shape = androidx.compose.ui.graphics.RectangleShape,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(dominant.copy(alpha = 0.85f), background.copy(alpha = 0.9f), background)),
            ),
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onCollapse) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_collapse), Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.labelMedium)
                    state.playback.deviceName?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = actions.onShowSleepTimer) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        stringResource(R.string.player_sleep_timer),
                        tint = if (state.sleepTimer.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    lyricsContent()
                } else {
                    CoverImage(
                        track?.imageUrl,
                        track?.album,
                        artworkModifier.fillMaxWidth().aspectRatio(1f),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }

            if (state.showFreeNotice) FreeTierNotice(Modifier.padding(vertical = 4.dp))

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier.clickable(enabled = track?.albumUri != null, onClick = actions.onAlbumClick),
                    )
                    Text(
                        track?.artists.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = track?.artistUri != null, onClick = actions.onArtistClick),
                    )
                }
                if (state.canLike) IconButton(onClick = actions.onLike, Modifier.testTag("like_button")) { LikeIcon(state.isLiked == true) }
            }

            SeekBar(state, actions.onSeek)
            Controls(state, actions)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onShowDevices) { Icon(Icons.Rounded.Devices, stringResource(R.string.player_devices)) }
                IconButton(onClick = actions.onToggleLyrics, modifier = Modifier.testTag("lyrics_toggle")) {
                    Icon(
                        Icons.Rounded.Lyrics,
                        stringResource(R.string.player_lyrics),
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                actions.onAddToPlaylist?.let { add ->
                    IconButton(onClick = add) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.player_add_to_playlist)) }
                }
                IconButton(onClick = actions.onShowQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue)) }
            }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.track?.durationMs ?: 0L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: state.progress
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = state.playback.restrictions.canSeek && duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            ),
            modifier = Modifier.testTag("seek_bar"),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, actions: PlayerActions) {
    val r = state.playback.restrictions
    val active = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onShuffle, enabled = r.canToggleShuffle) {
            Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (state.playback.shuffle) active else normal)
        }
        IconButton(onClick = actions.onPrevious, enabled = r.canSkipPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(40.dp))
        }
        FilledIconButton(
            onClick = actions.onTogglePlay,
            modifier = Modifier.size(72.dp).testTag("full_play_pause"),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
        ) {
            Icon(
                if (state.playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (state.playback.isPlaying) R.string.player_pause else R.string.player_play),
                Modifier.size(40.dp),
            )
        }
        IconButton(onClick = actions.onNext, enabled = r.canSkipNext, modifier = Modifier.size(56.dp).testTag("next_button")) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(40.dp))
        }
        IconButton(onClick = actions.onRepeat, enabled = r.canRepeat) {
            Icon(
                if (state.playback.repeat == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                stringResource(R.string.player_repeat),
                tint = if (state.playback.repeat != RepeatMode.Off) active else normal,
            )
        }
    }
}
