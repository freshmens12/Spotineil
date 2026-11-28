package com.spotineil.core.ui

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.spotineil.core.data.repository.MotionArtworkRepository
import com.spotineil.core.designsystem.component.CoverImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

/** Settings → Appearance → Animated artwork (and Data saver off). Provided by the app. */
val LocalAnimatedArtwork = staticCompositionLocalOf { false }

@HiltViewModel
class MotionArtworkViewModel @Inject constructor(private val repository: MotionArtworkRepository) : ViewModel() {
    suspend fun find(title: String, artist: String, album: String?): String? = repository.find(title, artist, album)
}

/**
 * The album's moving cover from Apple Music (a looping video link) for this song, once found;
 * null while looking, when it has none, or when animated artwork is off.
 */
@Composable
fun rememberMotionArtwork(title: String?, artist: String?, album: String?): String? {
    if (!LocalAnimatedArtwork.current || title.isNullOrBlank() || artist.isNullOrBlank()) return null
    val viewModel: MotionArtworkViewModel = hiltViewModel()
    val url by produceState<String?>(null, title, artist, album) {
        value = null
        value = viewModel.find(title, artist, album)
    }
    return url
}

/**
 * The cover picture, with the moving [motionUrl] video over it once its first frame is ready (it
 * fades in). The video is silent, loops, and runs only while the song plays and the app is open.
 */
@Composable
internal fun CoverArt(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier,
    shape: Shape,
    motionUrl: String?,
    isPlaying: Boolean,
) {
    if (motionUrl == null) {
        CoverImage(imageUrl, contentDescription, modifier, shape = shape)
        return
    }
    var ready by remember(motionUrl) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(700), label = "motion artwork")
    Box(modifier.clip(shape)) {
        CoverImage(imageUrl, contentDescription, Modifier.matchParentSize(), shape = RectangleShape)
        MotionVideo(
            motionUrl,
            isPlaying,
            Modifier.matchParentSize().graphicsLayer { this.alpha = alpha },
            onFirstFrame = { ready = true },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MotionVideo(url: String, isPlaying: Boolean, modifier: Modifier, onFirstFrame: () -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(HlsMediaSource.Factory(MotionVideoCache.dataSource(context)))
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                // No sound, and no bigger than a phone shows: less data.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(1080, 1080)
                    .build()
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = onFirstFrame()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, isPlaying, visible) { player.playWhenReady = isPlaying && visible }
    AndroidView(
        factory = { TextureView(it) }, // draws inside the cover's shape and moves with it
        modifier = modifier,
        update = { player.setVideoTextureView(it) },
        onRelease = { player.clearVideoTextureView(it) },
    )
}

/** Videos already watched stay on the phone (up to 150 MB), so a song's cover loops without new data. */
@OptIn(UnstableApi::class)
private object MotionVideoCache {
    @Volatile private var cache: SimpleCache? = null

    fun dataSource(context: Context): DataSource.Factory {
        val c = cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "motion-artwork"),
                LeastRecentlyUsedCacheEvictor(150L shl 20),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
