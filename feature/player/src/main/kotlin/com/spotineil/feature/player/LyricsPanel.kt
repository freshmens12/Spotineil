@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spotineil.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.foundation.combinedClickable
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
    /** Long press on a line: share it as a picture. */
    onShareLine: ((String) -> Unit)? = null,
    /** Opens the full-screen karaoke view. */
    onKaraoke: (() -> Unit)? = null,
    translation: TranslationState = TranslationState.Off,
    /** Turns the translation under each line on or off. */
    onTranslate: (() -> Unit)? = null,
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
                    LyricsList(lyrics, positionMs, onSeek, onShareLine, (translation as? TranslationState.Shown)?.lines)
                    Row(
                        Modifier.align(Alignment.TopEnd)
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (translation) {
                            TranslationState.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            TranslationState.SameLanguage -> Text(stringResource(R.string.player_translate_same), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TranslationState.Failed -> Text(stringResource(R.string.player_translate_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                        if (onTranslate != null) {
                            androidx.compose.material3.IconButton(onClick = onTranslate) {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Translate,
                                    stringResource(R.string.player_translate),
                                    tint = if (translation != TranslationState.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (onKaraoke != null) {
                            androidx.compose.material3.IconButton(onClick = onKaraoke) {
                                androidx.compose.material3.Icon(Icons.Rounded.Fullscreen, stringResource(R.string.player_karaoke))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsList(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, onShareLine: ((String) -> Unit)?, translated: List<String>?) {
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
                    .combinedClickable(
                        onClick = { if (lyrics.isSynced) onSeek(line.timeMs) },
                        onLongClick = onShareLine?.takeIf { line.text.isNotBlank() }?.let { share -> { share(line.text) } },
                    )
                    .padding(vertical = 8.dp)
                    .testTag("lyric_line_$i"),
            )
            translated?.getOrNull(i)?.takeIf { it.isNotBlank() && line.text.isNotBlank() }?.let { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.titleMedium,
                    color = color.copy(alpha = color.alpha * 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
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
