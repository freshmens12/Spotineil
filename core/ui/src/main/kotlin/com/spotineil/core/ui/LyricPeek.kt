package com.spotineil.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotineil.core.model.Lyrics

/**
 * The line being sung, under the song's name on Now playing (as the lock screen shows it). Each
 * new line slides up into place. Tapping it opens the full lyrics. Shows nothing for songs
 * without lyrics; lyrics without timings show their first line.
 */
@Composable
fun LyricPeek(lyrics: Lyrics?, positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val l = lyrics?.takeIf { !it.isInstrumental && it.lines.any { line -> line.text.isNotBlank() } } ?: return
    val index = if (l.isSynced) l.indexAt(positionMs) else -1
    // Before the first line: the first one, dimmed, so it's clear what's coming.
    val upcoming = l.isSynced && index < 0
    val text = if (index >= 0) l.lines[index].text.trim().ifEmpty { "♪" } else l.lines.first { it.text.isNotBlank() }.text.trim()
    val style = MaterialTheme.typography.titleMedium
    // Room for two lines, always, so the buttons below don't jump as lines change (a short line sits at the top).
    val height = with(LocalDensity.current) { (style.lineHeight * 2).toDp() }
    val reducedMotion = rememberReducedMotion()

    // Just the words: no box or icon around them. Tapping anywhere on the line opens the lyrics.
    Box(
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.ui_lyrics_open),
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("lyric_peek"),
    ) {
        AnimatedContent(
            targetState = Triple(index, text, upcoming),
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                        (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
                } using SizeTransform(clip = true)
            },
            contentAlignment = Alignment.TopStart,
            modifier = Modifier.fillMaxWidth().height(height),
            label = "lyric line",
        ) { (_, line, dim) ->
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.TopStart) {
                Text(
                    line,
                    style = style,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.55f else 1f),
                )
            }
        }
    }
}
