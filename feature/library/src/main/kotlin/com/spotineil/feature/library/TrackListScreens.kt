package com.spotineil.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MessageState
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.formatDuration
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.ui.TrackActionTarget
import com.spotineil.core.ui.TrackActionsSheet
import com.spotineil.core.ui.toActionTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsRoute(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: LikedSongsViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()
    val nowPlaying by viewModel.nowPlayingUri.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<TrackActionTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_liked_songs)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { viewModel.playAll(shuffle = true) }) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.library_shuffle)) }
                    IconButton(onClick = { viewModel.playAll(shuffle = false) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.library_play)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 16.dp),
        ) {
            val refresh = tracks.loadState.refresh
            if (tracks.itemCount == 0) {
                item {
                    when (refresh) {
                        is LoadState.Loading -> Column { repeat(8) { SkeletonTrackRow() } }
                        is LoadState.Error -> ErrorState(refresh.error, onRetry = { tracks.retry() })
                        else -> MessageState(Icons.Rounded.FolderOff, stringResource(R.string.library_no_liked), null)
                    }
                }
            }
            items(tracks.itemCount, key = { i -> tracks.peek(i)?.let { "${it.id}:$i" } ?: i }) { i ->
                tracks[i]?.let { t ->
                    TrackRow(
                        t.name, t.artistNames,
                        onClick = { viewModel.play(i, t) },
                        imageUrl = rememberImageUrl(t.images, 120),
                        explicit = t.explicit,
                        isCurrent = t.uri == nowPlaying,
                        enabled = t.isPlayable,
                        onMore = { target = t.toActionTarget() },
                    )
                }
            }
        }
    }
    target?.let { TrackActionsSheet(it, { target = null }, onArtistClick, onAlbumClick, onMessage) }
}

