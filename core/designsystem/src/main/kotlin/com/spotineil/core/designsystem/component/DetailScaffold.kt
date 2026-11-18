package com.spotineil.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotineil.core.designsystem.R
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding

/**
 * Album / playlist / artist / profile page: a collapsing header tinted with a color
 * extracted from the artwork (Palette API), a parallax cover, and a top bar whose
 * background and title fade in as the header scrolls away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    imageUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageShape: Shape = MaterialTheme.shapes.small,
    listState: LazyListState = rememberLazyListState(),
    headerMeta: @Composable () -> Unit = {},
    actionRow: @Composable RowScope.() -> Unit = {},
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val dominant by rememberDominantColor(imageUrl)
    val headerHeightPx = with(LocalDensity.current) { 300.dp.toPx() }
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / headerHeightPx).coerceIn(0f, 1f)
        }
    }

    Box(modifier.fillMaxSize().background(background)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(dominant, dominant.copy(alpha = 0.45f), background)))
                        .statusBarsPadding()
                        .padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverImage(
                        url = imageUrl,
                        contentDescription = title,
                        shape = imageShape,
                        modifier = Modifier
                            .size(232.dp)
                            .graphicsLayer {
                                val c = collapse
                                translationY = c * headerHeightPx * 0.35f
                                scaleX = 1f - c * 0.25f
                                scaleY = 1f - c * 0.25f
                                alpha = 1f - c
                            },
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            item(key = "meta") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { headerMeta() }
            }
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actionRow()
                    Spacer(Modifier.weight(1f))
                    if (onShuffle != null) {
                        IconButton(onClick = onShuffle) {
                            Icon(Icons.Rounded.Shuffle, stringResource(R.string.ds_shuffle_play), Modifier.size(28.dp))
                        }
                    }
                    if (onPlay != null) {
                        FloatingActionButton(
                            onClick = onPlay,
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.ds_play), Modifier.size(32.dp))
                        }
                    }
                }
            }
            content()
        }

        TopAppBar(
            title = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = ((collapse - 0.6f) / 0.4f).coerceIn(0f, 1f) },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ds_back))
                }
            },
            actions = topBarActions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = lerp(Color.Transparent, dominant.darkenForBar(background), collapse),
            ),
        )
    }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

private fun Color.darkenForBar(bg: Color): Color = androidx.compose.ui.graphics.lerp(this, bg, 0.35f)
