package com.spotineil.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.model.Lyrics

/**
 * Lyrics from LRCLIB. Synced lyrics auto-scroll and highlight the current line; tapping a
 * line seeks to it. Auto-scroll pauses briefly while the user scrolls manually.
 */
@Composable
fun LyricsPanel(
    state: LyricsUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("lyrics_panel"), contentAlignment = Alignment.Center) {
        when (state) {
            LyricsUiState.Idle -> Unit
            LyricsUiState.Loading -> CircularProgressIndicator()
            LyricsUiState.NotAvailable -> MessageState(Icons.Rounded.MusicOff, stringResource(R.string.player_lyrics_unavailable), null)
            is LyricsUiState.Loaded -> {
                val lyrics = state.lyrics
                if (lyrics.isInstrumental) {
                    MessageState(Icons.Rounded.Piano, stringResource(R.string.player_lyrics_instrumental), null)
                } else {
                    LyricsList(lyrics, positionMs, onSeek)
                }
            }
        }
    }
}

@Composable
private fun LyricsList(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val current = lyrics.indexAt(positionMs)
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    if (isDragged) lastUserScroll = System.currentTimeMillis()

    LaunchedEffect(current) {
        if (!lyrics.isSynced || current < 0) return@LaunchedEffect
        if (System.currentTimeMillis() - lastUserScroll < 3_000) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
        if (info != null) {
            // Smoothly bring the active line to ~1/3 of the viewport.
            listState.animateScrollBy((info.offset - viewport / 3).toFloat())
        } else {
            listState.animateScrollToItem(current, -viewport / 3)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.timeMs}" }) { i, line ->
            val active = lyrics.isSynced && i == current
            val past = lyrics.isSynced && i < current
            val color by animateColorAsState(
                when {
                    !lyrics.isSynced -> MaterialTheme.colorScheme.onSurface
                    active -> MaterialTheme.colorScheme.onSurface
                    past -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                },
                label = "lyricColor",
            )
            Text(
                text = line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = lyrics.isSynced) { onSeek(line.timeMs) }
                    .padding(vertical = 8.dp)
                    .testTag("lyric_line_$i"),
            )
        }
        item {
            Text(
                stringResource(R.string.player_lyrics_source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
