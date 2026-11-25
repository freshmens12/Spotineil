package com.spotineil.core.player

import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotineil.core.common.ApplicationScope
import com.spotineil.core.data.repository.ConnectRepository
import com.spotineil.core.model.Device
import com.spotineil.core.model.LocalTrack
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.toPlayable
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.RemoteConnectionState
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.player.connect.ConnectEngine
import com.spotineil.core.player.local.LocalEngine
import com.spotineil.core.player.remote.SpotifyRemoteEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class DefaultPlaybackController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val remote: SpotifyRemoteEngine,
    private val connect: ConnectEngine,
    private val local: LocalEngine,
    private val connectRepository: ConnectRepository,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) : PlaybackController {

    /** True after the user starts local playback; false once Spotify content is requested. */
    private val localActive = MutableStateFlow(false)
    private var inForeground = false

    override val state: StateFlow<PlaybackState> = combine(
        remote.state, connect.state, local.state, localActive,
    ) { r, c, l, isLocal ->
        when {
            isLocal && l != null -> l
            r != null && r.track != null -> r
            c != null && c.track != null -> c
            l != null -> l
            else -> PlaybackState()
        }
    }.stateIn(scope, SharingStarted.Eagerly, PlaybackState())

    override val connection: StateFlow<RemoteConnectionState> = remote.connection
    override val canPlayOnDemand: StateFlow<Boolean?> = remote.canPlayOnDemand

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    override val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val voiceModels = com.spotineil.core.player.local.VoiceModelStore(appContext)
    private var voiceModelDownload: kotlinx.coroutines.Job? = null

    init {
        com.spotineil.core.player.local.KaraokeAudio.loadModel = { voiceModels.load() }
    }

    override val voiceRemoval: StateFlow<VoiceRemoval> = com.spotineil.core.player.local.KaraokeAudio.state

    override val voiceModelDownloaded: Boolean get() = voiceModels.installed()

    override fun setKaraokeOpen(open: Boolean) {
        if (!open) voiceModelDownload?.cancel()
        com.spotineil.core.player.local.KaraokeAudio.setArmed(open)
    }

    override fun setVocalsRemoved(removed: Boolean) {
        val karaoke = com.spotineil.core.player.local.KaraokeAudio
        voiceModelDownload?.cancel()
        if (!removed || voiceModels.installed()) {
            karaoke.setRemoveVoice(removed)
            return
        }
        voiceModelDownload = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            karaoke.setState(VoiceRemoval.Downloading(0f))
            val ok = runCatching {
                kotlinx.coroutines.runInterruptible {
                    voiceModels.download { p -> karaoke.setState(VoiceRemoval.Downloading(p)) }
                }
            }.isSuccess
            if (!isActive) return@launch
            if (ok && karaoke.armed) karaoke.setRemoveVoice(true)
            else karaoke.setState(if (ok) VoiceRemoval.Off else VoiceRemoval.Failed)
        }
    }
    private var sleepJob: Job? = null

    init {
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    inForeground = true
                    updatePolling()
                }

                override fun onStop(owner: LifecycleOwner) {
                    inForeground = false
                    connect.stopPolling()
                }
            })
        }
        scope.launch { remote.connection.collect { updatePolling() } }
    }

    /** Poll the Web API only when App Remote can't give us push updates. */
    private fun updatePolling() {
        if (inForeground && !remote.isConnected) connect.startPolling() else connect.stopPolling()
    }

    private enum class Route { Local, Remote, Connect, None }

    private fun route(): Route = when {
        // Once the user started a song in Spotineil, its own player handles every control, even
        // for the moment while the next song is still loading and has no media yet.
        localActive.value -> Route.Local
        remote.isConnected -> Route.Remote
        // No Spotify account (the free app): there is nothing to remote-control.
        !connect.isAvailable -> Route.None
        else -> Route.Connect
    }

    /** Commands run on the main thread: required by MediaController, and safe for App Remote. */
    private fun command(block: suspend () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _events.tryEmit(e.toPlayerEvent())
            }
        }
    }

    private fun Throwable.toPlayerEvent(): PlayerEvent = when (this) {
        is SpotineilException.Forbidden -> PlayerEvent.PremiumRequired
        is SpotineilException.NotFound -> PlayerEvent.NoActiveDevice
        is CouldNotFindSpotifyApp -> PlayerEvent.SpotifyNotInstalled
        is NotLoggedInException -> PlayerEvent.SpotifyNotLoggedIn
        else -> PlayerEvent.Error(message)
    }

    override fun connectSpotify(context: Context) = remote.connect(context, showAuthView = true)

    override fun disconnectSpotify() {
        remote.disconnect()
        connect.stopPolling()
    }

    /**
     * Reconnects App Remote (without UI) when the process was cold, e.g. a widget tap, so
     * Spotify content keeps going through the Spotify app rather than the Premium-only Web API.
     */
    private suspend fun ensureRemote() {
        if (!remote.isConnected && remote.isSpotifyInstalled(appContext)) remote.connectAndAwait(appContext)
    }

    private suspend fun routeEnsuringRemote(): Route {
        if (!(localActive.value && local.hasMedia)) ensureRemote()
        return route()
    }

    override fun play(request: PlayRequest) = command {
        localActive.value = false
        local.pause()
        ensureRemote()
        if (remote.isConnected) {
            remote.play(request)
        } else {
            request.shuffle?.let { connectRepository.setShuffle(it) }
            when {
                request.contextUri != null -> connectRepository.play(contextUri = request.contextUri, offsetIndex = request.index)
                request.uri != null -> connectRepository.play(uris = listOf(request.uri))
            }
            connect.refreshSoon()
        }
    }

    override fun playLocal(tracks: List<LocalTrack>, startIndex: Int) =
        playMedia(tracks.map { it.toPlayable() }, startIndex)

    override fun playMedia(items: List<PlayableMedia>, startIndex: Int, startPositionMs: Long) = command {
        if (items.isEmpty()) return@command
        if (remote.isConnected && remote.state.value?.isPlaying == true) runCatching { remote.pause() }
        localActive.value = true
        local.play(items, startIndex, startPositionMs)
    }

    override fun togglePlayPause() = command {
        val playing = state.value.isPlaying
        when (routeEnsuringRemote()) {
            Route.Local -> local.togglePlayPause()
            Route.Remote -> if (playing) remote.pause() else remote.resume()
            Route.None -> Unit
            Route.Connect -> {
                if (playing) connectRepository.pause() else connectRepository.resume()
                connect.refreshSoon()
            }
        }
    }

    override fun next() = command {
        when (routeEnsuringRemote()) {
            Route.Local -> local.next()
            Route.Remote -> remote.next()
            Route.None -> Unit
            Route.Connect -> { connectRepository.next(); connect.refreshSoon() }
        }
    }

    override fun previous() = command {
        when (routeEnsuringRemote()) {
            Route.Local -> local.previous()
            Route.Remote -> remote.previous()
            Route.None -> Unit
            Route.Connect -> { connectRepository.previous(); connect.refreshSoon() }
        }
    }

    override fun seekTo(positionMs: Long) = command {
        when (route()) {
            Route.Local -> local.seekTo(positionMs)
            Route.Remote -> remote.seekTo(positionMs)
            Route.None -> Unit
            Route.Connect -> { connectRepository.seek(positionMs); connect.refreshSoon() }
        }
    }

    override fun toggleShuffle() = command {
        val enabled = !state.value.shuffle
        when (route()) {
            Route.Local -> local.toggleShuffle()
            Route.Remote -> remote.setShuffle(enabled)
            Route.None -> Unit
            Route.Connect -> { connectRepository.setShuffle(enabled); connect.refreshSoon() }
        }
    }

    override fun cycleRepeat() = command {
        val mode = state.value.repeat.next()
        when (route()) {
            Route.Local -> local.cycleRepeat()
            Route.Remote -> remote.setRepeat(mode)
            Route.None -> Unit
            Route.Connect -> { connectRepository.setRepeat(mode); connect.refreshSoon() }
        }
    }

    override fun addToQueue(uri: String) = command {
        when (route()) {
            Route.Local -> if (uri.startsWith("ytmusic:")) local.addToQueue(uri)
                else _events.tryEmit(PlayerEvent.Error("Spotify tracks can't be queued during local playback"))
            Route.Remote -> remote.queue(uri)
            Route.None -> Unit
            Route.Connect -> connectRepository.addToQueue(uri)
        }
    }

    override fun setUpNext(items: List<PlayableMedia>) = command {
        if (route() == Route.Local) local.setUpNext(items)
    }

    override suspend fun queue(): PlaybackQueue =
        if (route() == Route.Local) local.queue() else connectRepository.queue()

    override fun moveQueueItem(from: Int, to: Int) {
        if (route() == Route.Local) command { local.moveUpcoming(from, to); _queueEdits.tryEmit(Unit) }
    }

    private val _queueEdits = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val queueEdits: SharedFlow<Unit> = _queueEdits.asSharedFlow()

    override fun playNext(items: List<PlayableMedia>) {
        if (items.isEmpty()) return
        if (!localActive.value || !local.hasMedia) return playMedia(items, 0)
        command { local.insertNext(items); _queueEdits.tryEmit(Unit) }
    }

    override fun addToQueueEnd(items: List<PlayableMedia>) {
        if (items.isEmpty()) return
        if (!localActive.value || !local.hasMedia) return playMedia(items, 0)
        command { local.append(items); _queueEdits.tryEmit(Unit) }
    }

    override fun removeQueueItem(index: Int) {
        if (route() == Route.Local) command { local.removeUpcoming(index); _queueEdits.tryEmit(Unit) }
    }

    override suspend fun devices(): List<Device> = connectRepository.devices()

    override fun transferTo(device: Device) = command {
        val id = device.id ?: return@command
        localActive.value = false
        local.pause()
        connectRepository.transfer(id, play = true)
        connect.refreshSoon()
    }

    override fun setSleepTimer(option: SleepTimerOption?) {
        sleepJob?.cancel()
        _sleepTimer.value = SleepTimerState()
        if (option == null) return
        sleepJob = scope.launch {
            try {
                when (option) {
                    is SleepTimerOption.Minutes -> {
                        val endsAt = clock.now() + option.minutes * 60_000L
                        _sleepTimer.value = SleepTimerState(endsAtMs = endsAt)
                        delay((endsAt - clock.now() - SLEEP_FADE_MS).coerceAtLeast(0))
                        fadeOut((endsAt - clock.now()).coerceIn(0, SLEEP_FADE_MS))
                    }
                    SleepTimerOption.EndOfTrack -> {
                        _sleepTimer.value = SleepTimerState(endOfTrack = true)
                        val startUri = state.value.track?.uri
                        while (isActive) {
                            val s = state.value
                            val duration = s.track?.durationMs ?: 0
                            val remaining = duration - s.estimatedPosition(clock.now())
                            if (s.track?.uri != startUri) break
                            if (duration > 0 && remaining <= SLEEP_FADE_MS) {
                                fadeOut((remaining - 500).coerceAtLeast(0))
                                break
                            }
                            delay(500)
                        }
                    }
                }
                if (state.value.isPlaying) pauseNow()
            } finally {
                // Back to full volume for next time (also when the timer is turned off mid-fade).
                if (route() == Route.Local) withContext(NonCancellable) { runCatching { local.setVolume(1f) } }
                _sleepTimer.value = SleepTimerState()
            }
        }
    }

    /** Lowers the volume to silence over [durationMs] (on-device playback only). */
    private suspend fun fadeOut(durationMs: Long) {
        if (route() != Route.Local || durationMs <= 0) return
        val steps = 30
        for (i in 1..steps) {
            runCatching { local.setVolume(1f - i.toFloat() / steps) }
            delay(durationMs / steps)
        }
    }

    private suspend fun pauseNow() {
        runCatching {
            when (route()) {
                Route.Local -> local.pause()
                Route.Remote -> remote.pause()
                Route.None -> Unit
                Route.Connect -> connectRepository.pause()
            }
        }
    }
}
private const val SLEEP_FADE_MS = 30_000L
