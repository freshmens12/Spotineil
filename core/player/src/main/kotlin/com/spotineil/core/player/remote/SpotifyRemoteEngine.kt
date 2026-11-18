package com.spotineil.core.player.remote

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Capabilities
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.model.NowPlayingTrack
import com.spotineil.core.model.PlaybackRestrictions
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.RemoteConnectionState
import com.spotineil.core.model.RepeatMode
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.player.PlayRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Wraps the official Spotify App Remote SDK. Requires the Spotify app on the device. */
@Singleton
class SpotifyRemoteEngine @Inject constructor(
    private val config: SpotifyAppConfig,
    private val clock: Clock,
) {
    @Volatile private var remote: SpotifyAppRemote? = null
    private var playerSubscription: Subscription<PlayerState>? = null
    private var capabilitySubscription: Subscription<Capabilities>? = null

    private val _connection = MutableStateFlow(RemoteConnectionState.Disconnected)
    val connection: StateFlow<RemoteConnectionState> = _connection.asStateFlow()

    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private val _canPlayOnDemand = MutableStateFlow<Boolean?>(null)
    val canPlayOnDemand: StateFlow<Boolean?> = _canPlayOnDemand.asStateFlow()

    var lastError: Throwable? = null
        private set

    val isConnected: Boolean get() = remote?.isConnected == true

    fun isSpotifyInstalled(context: Context): Boolean = SpotifyAppRemote.isSpotifyInstalled(context)

    fun connect(context: Context, showAuthView: Boolean) {
        if (!config.isConfigured) return
        if (!isSpotifyInstalled(context)) {
            _connection.value = RemoteConnectionState.SpotifyNotInstalled
            return
        }
        if (isConnected || _connection.value == RemoteConnectionState.Connecting) return
        _connection.value = RemoteConnectionState.Connecting
        val params = ConnectionParams.Builder(config.clientId)
            .setRedirectUri(config.redirectUri)
            .showAuthView(showAuthView)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                lastError = null
                _connection.value = RemoteConnectionState.Connected
                subscribe(appRemote)
            }

            override fun onFailure(error: Throwable) {
                // Also invoked when an established connection drops.
                lastError = error
                cleanup()
                _connection.value = if (error is CouldNotFindSpotifyApp) {
                    RemoteConnectionState.SpotifyNotInstalled
                } else {
                    RemoteConnectionState.Failed
                }
            }
        })
    }

    /** Connects (without UI) and waits briefly; returns whether we ended up connected. */
    suspend fun connectAndAwait(context: Context, timeoutMs: Long = 6_000): Boolean {
        connect(context, showAuthView = false)
        return withTimeoutOrNull(timeoutMs) {
            connection.first { it != RemoteConnectionState.Connecting }
        } == RemoteConnectionState.Connected
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        cleanup()
        _connection.value = RemoteConnectionState.Disconnected
    }

    private fun cleanup() {
        playerSubscription?.cancel()
        capabilitySubscription?.cancel()
        playerSubscription = null
        capabilitySubscription = null
        remote = null
        _state.value = null
    }

    private fun subscribe(appRemote: SpotifyAppRemote) {
        playerSubscription = appRemote.playerApi.subscribeToPlayerState().setEventCallback { ps ->
            _state.value = ps.toModel()
        }
        capabilitySubscription = appRemote.userApi.subscribeToCapabilities().setEventCallback { caps ->
            _canPlayOnDemand.value = caps.canPlayOnDemand
        }
    }

    private fun requireRemote(): SpotifyAppRemote =
        remote?.takeIf { it.isConnected } ?: throw IllegalStateException("Spotify App Remote is not connected")

    suspend fun play(request: PlayRequest) {
        val api = requireRemote().playerApi
        request.shuffle?.let { api.setShuffle(it).await() }
        when {
            request.contextUri != null && request.index != null -> api.skipToIndex(request.contextUri, request.index).await()
            request.contextUri != null -> api.play(request.contextUri).await()
            request.uri != null -> api.play(request.uri).await()
        }
    }

    suspend fun resume() { requireRemote().playerApi.resume().await() }
    suspend fun pause() { requireRemote().playerApi.pause().await() }
    suspend fun next() { requireRemote().playerApi.skipNext().await() }
    suspend fun previous() { requireRemote().playerApi.skipPrevious().await() }
    suspend fun seekTo(positionMs: Long) { requireRemote().playerApi.seekTo(positionMs).await() }
    suspend fun setShuffle(enabled: Boolean) { requireRemote().playerApi.setShuffle(enabled).await() }
    suspend fun setRepeat(mode: RepeatMode) {
        val value = when (mode) { RepeatMode.Off -> Repeat.OFF; RepeatMode.All -> Repeat.ALL; RepeatMode.One -> Repeat.ONE }
        requireRemote().playerApi.setRepeat(value).await()
    }
    suspend fun queue(uri: String) { requireRemote().playerApi.queue(uri).await() }

    private fun PlayerState.toModel(): PlaybackState {
        val t = track
        return PlaybackState(
            source = PlaybackSource.SpotifyAppRemote,
            track = t?.let {
                NowPlayingTrack(
                    uri = it.uri.orEmpty(),
                    name = it.name.orEmpty(),
                    artists = it.artists?.joinToString { a -> a.name.orEmpty() }?.takeIf { s -> s.isNotBlank() }
                        ?: it.artist?.name.orEmpty(),
                    artistUri = it.artist?.uri,
                    album = it.album?.name.orEmpty(),
                    albumUri = it.album?.uri,
                    durationMs = it.duration,
                    imageUrl = it.imageUri?.raw?.toImageUrl(),
                    isEpisode = it.isEpisode,
                )
            },
            isPlaying = !isPaused,
            positionMs = playbackPosition,
            positionTimestamp = clock.now(),
            shuffle = playbackOptions?.isShuffling ?: false,
            repeat = when (playbackOptions?.repeatMode) {
                Repeat.ALL -> RepeatMode.All
                Repeat.ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            restrictions = playbackRestrictions?.let {
                PlaybackRestrictions(
                    canSkipNext = it.canSkipNext,
                    canSkipPrevious = it.canSkipPrev,
                    canSeek = it.canSeek,
                    canToggleShuffle = it.canToggleShuffle,
                    canRepeat = it.canRepeatContext || it.canRepeatTrack,
                )
            } ?: PlaybackRestrictions(),
        )
    }
}

/** `spotify:image:<id>` → CDN URL. */
internal fun String.toImageUrl(): String? =
    if (startsWith("spotify:image:")) "https://i.scdn.co/image/" + substringAfterLast(':') else null

private suspend fun <T> CallResult<T>.await(): T = suspendCancellableCoroutine { cont ->
    setResultCallback { if (cont.isActive) cont.resume(it) }
    setErrorCallback { if (cont.isActive) cont.resumeWithException(it) }
    cont.invokeOnCancellation { cancel() }
}
