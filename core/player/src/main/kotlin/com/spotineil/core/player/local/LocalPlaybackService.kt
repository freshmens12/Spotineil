package com.spotineil.core.player.local

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.spotineil.core.data.repository.LocalMediaRepository
import com.spotineil.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.spotineil.core.model.LocalTrack
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import com.spotineil.core.player.youtube.RoutingDataSourceFactory
import com.spotineil.core.player.youtube.YouTubeStreamDataSourceFactory

/**
 * Plays audio files from the device with ExoPlayer. Media3 provides the media notification,
 * lock-screen controls and Bluetooth/headset buttons; the library tree makes local files
 * browsable from Android Auto.
 */
/**
 * A song whose file is gone or unreachable (free catalogs occasionally list tracks whose audio
 * returns 404) is skipped instead of stopping the queue. After [MAX_SKIPS] failures in a row
 * (e.g. the phone is offline) it stops and leaves the error showing.
 */
internal class SkipUnplayableListener(private val player: ExoPlayer) : Player.Listener {
    private var consecutiveSkips = 0

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) consecutiveSkips = 0
    }

    override fun onPlayerError(error: PlaybackException) {
        // 2xxx = input/output (404, timeouts), 3xxx = bad file, 4xxx = can't decode.
        val isSourceError = error.errorCode in 2000..4999
        if (!isSourceError || !player.hasNextMediaItem() || consecutiveSkips >= MAX_SKIPS) return
        consecutiveSkips++
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    private companion object {
        const val MAX_SKIPS = 5
    }
}

@AndroidEntryPoint
class LocalPlaybackService : MediaLibraryService() {

    @Inject lateinit var localMedia: LocalMediaRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var youTubeStreamFactory: YouTubeStreamDataSourceFactory

    private var session: MediaLibrarySession? = null
    private var transitions: TransitionController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(this), youTubeStreamFactory)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(SkipUnplayableListener(player))

        // Crossfade / AutoMix between songs: a second player carries the end of the old song.
        val sourceFactory = DefaultMediaSourceFactory(RoutingDataSourceFactory(DefaultDataSource.Factory(this), youTubeStreamFactory))
        transitions = TransitionController(player) {
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(sourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    /* handleAudioFocus = */ false,
                )
                .build()
        }.also { controller ->
            controller.start()
            scope.launch {
                settings.settings.map { it.transition }.distinctUntilChanged().collect { controller.mode = it }
            }
        }

        val builder = MediaLibrarySession.Builder(this, player, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        transitions?.release()
        transitions = null
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT_ID, "Spotineil"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
            val children = when (parentId) {
                ROOT_ID -> listOf(folderItem(SONGS_ID, "Local files"))
                SONGS_ID -> localMedia.tracks()
                    .drop(page * pageSize).take(pageSize)
                    .map(LocalTrack::toMediaItem)
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = async {
            val item = resolve(mediaId)
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /** Android Auto / external controllers send items by id only; attach the playable URI. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = async {
            mediaItems.mapNotNull { item ->
                when {
                    item.localConfiguration != null -> item
                    item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                    else -> resolve(item.mediaId)
                }
            }.toMutableList()
        }
    }

    private suspend fun resolve(mediaId: String): MediaItem? {
        val id = mediaId.removePrefix(LOCAL_ID_PREFIX).toLongOrNull() ?: return null
        return localMedia.tracks().firstOrNull { it.id == id }?.toMediaItem()
    }

    private fun <T> async(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            runCatching { block() }
                .onSuccess { future.set(it) }
                .onFailure { future.setException(it) }
        }
        return future
    }
}
