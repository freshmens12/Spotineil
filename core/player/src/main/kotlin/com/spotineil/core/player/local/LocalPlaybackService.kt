package com.spotineil.core.player.local

import com.spotineil.core.player.R
import com.spotineil.core.model.RecentItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.CommandButton
import android.os.Bundle
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
    @Inject lateinit var liked: com.spotineil.core.data.repository.LikedMediaRepository
    @Inject lateinit var userPlaylists: com.spotineil.core.data.repository.UserPlaylistRepository
    @Inject lateinit var recents: com.spotineil.core.data.repository.RecentsRepository
    @Inject lateinit var youTubeStreamFactory: YouTubeStreamDataSourceFactory
    @Inject lateinit var lyricsRepository: com.spotineil.core.data.repository.LyricsRepository

    private var session: MediaLibrarySession? = null
    private var transitions: TransitionController? = null
    private var effects: AudioEffectsController? = null
    private var lockScreenLyrics: LockScreenLyrics? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, karaokeRenderers())
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
        transitions = TransitionController(
            player,
            prefetch = { item -> youTubeStreamFactory.prefetch(item.localConfiguration?.uri) },
        ) {
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
        // Equalizer, volume leveling, skip silence and podcast speed (Settings → Sound).
        effects = AudioEffectsController(player).also { fx ->
            fx.start()
            scope.launch {
                settings.settings.map { it.audio }.distinctUntilChanged().collect { fx.update(it) }
            }
        }

        // The session sees the player through LyricsSessionPlayer: lyric lines on the lock screen.
        val sessionPlayer = LyricsSessionPlayer(player)
        lockScreenLyrics = LockScreenLyrics(player, sessionPlayer, lyricsRepository, scope).also { lyrics ->
            lyrics.start()
            scope.launch {
                settings.settings.map { it.lockScreenLyrics }.distinctUntilChanged().collect { lyrics.enabled = it }
            }
        }

        val builder = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
        // The heart on the notification follows the song now playing.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = watchLike(mediaItem)
        })
        watchLike(player.currentMediaItem)
    }

    /**
     * Renderers whose audio goes through the karaoke vocal remover (only the main player: the
     * crossfade tail player just plays the end of the previous song).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun karaokeRenderers() = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: android.content.Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink {
            val karaoke = KaraokeAudioProcessor()
            val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(karaoke))
                .build()
            return sink
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        lockScreenLyrics?.release()
        lockScreenLyrics = null
        transitions?.release()
        effects?.release()
        effects = null
        transitions = null
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Like button on the notification / lock screen ----------------

    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    private var likeJob: Job? = null
    private var currentLiked = false

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (liked) R.string.player_notification_unlike else R.string.player_notification_like))
            .setSessionCommand(likeCommand)
            .build()

    /** Shows a filled or empty heart for the song now playing (none for live radio). */
    private fun watchLike(item: MediaItem?) {
        likeJob?.cancel()
        val s = session ?: return
        val live = item?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_LIVE) == true
        if (item == null || live || item.mediaId.isBlank()) {
            s.setCustomLayout(ImmutableList.of())
            return
        }
        likeJob = scope.launch {
            liked.isLiked(item.mediaId).collect { value ->
                currentLiked = value
                s.setCustomLayout(ImmutableList.of(likeButton(value)))
            }
        }
    }

    // ---------------- Library tree (Android Auto) ----------------

    /** The songs of the folders opened last, so tapping one song plays the whole folder. */
    private val browsedFolders = LinkedHashMap<String, List<MediaItem>>()

    private suspend fun folderSongs(parentId: String): List<MediaItem> = when {
        parentId == LIKED_ID -> liked.liked.first().map { it.toMediaItem() }
        parentId == RECENT_ID -> recents.recent(RECENT_LIMIT).first().filterIsInstance<RecentItem.Song>().map { it.media.toMediaItem() }
        parentId == SONGS_ID -> localMedia.tracks().map(LocalTrack::toMediaItem)
        parentId.startsWith(PLAYLIST_PREFIX) -> userPlaylists.songs(parentId.removePrefix(PLAYLIST_PREFIX)).first().map { it.toMediaItem() }
        else -> emptyList()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(likeCommand).build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_LIKE) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val item = session.player.currentMediaItem ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            val media = item.toPlayableMedia()
            val value = !currentLiked
            scope.launch { liked.setLiked(media, value) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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
                ROOT_ID -> listOf(
                    folderItem(LIKED_ID, getString(R.string.player_auto_liked)),
                    folderItem(PLAYLISTS_ID, getString(R.string.player_auto_playlists)),
                    folderItem(RECENT_ID, getString(R.string.player_auto_recent)),
                    folderItem(SONGS_ID, getString(R.string.player_auto_local)),
                )
                PLAYLISTS_ID -> userPlaylists.playlists.first().map { folderItem(PLAYLIST_PREFIX + it.id, it.name) }
                else -> folderSongs(parentId).also { songs ->
                    browsedFolders.remove(parentId)
                    browsedFolders[parentId] = songs
                    while (browsedFolders.size > 6) browsedFolders.remove(browsedFolders.keys.first())
                }
            }
            val pageItems = if (pageSize > 0) children.drop(page * pageSize).take(pageSize) else children
            LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
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
        ): ListenableFuture<MutableList<MediaItem>> = async { withUris(mediaItems) }

        /** One song picked from a folder in the car: play the whole folder, starting at that song. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = async {
            val single = mediaItems.singleOrNull()
            val folder = single?.let { pick -> browsedFolders.values.lastOrNull { list -> list.any { it.mediaId == pick.mediaId } } }
            if (single != null && folder != null && folder.size > 1) {
                MediaSession.MediaItemsWithStartPosition(folder, folder.indexOfFirst { it.mediaId == single.mediaId }, startPositionMs)
            } else {
                MediaSession.MediaItemsWithStartPosition(withUris(mediaItems), startIndex.coerceAtLeast(0), startPositionMs)
            }
        }
    }

    private suspend fun withUris(mediaItems: List<MediaItem>): MutableList<MediaItem> =
        mediaItems.mapNotNull { item ->
            when {
                item.localConfiguration != null -> item
                item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                else -> resolve(item.mediaId)
            }
        }.toMutableList()

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

private const val ACTION_LIKE = "spotineil.LIKE"
private const val LIKED_ID = "liked"
private const val PLAYLISTS_ID = "playlists"
private const val RECENT_ID = "recent"
private const val PLAYLIST_PREFIX = "playlist:"
private const val RECENT_LIMIT = 50
