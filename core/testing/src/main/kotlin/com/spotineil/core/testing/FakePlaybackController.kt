package com.spotineil.core.testing

import android.content.Context
import com.spotineil.core.model.Device
import com.spotineil.core.model.LocalTrack
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.RemoteConnectionState
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.player.PlayerEvent
import com.spotineil.core.player.SleepTimerOption
import com.spotineil.core.player.SleepTimerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records every command so tests can assert on them. */
class FakePlaybackController : PlaybackController {
    override val state = MutableStateFlow(PlaybackState())
    override val connection = MutableStateFlow(RemoteConnectionState.Connected)
    override val canPlayOnDemand = MutableStateFlow<Boolean?>(true)
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override val sleepTimer = MutableStateFlow(SleepTimerState())
    override val voiceRemoval = MutableStateFlow<com.spotineil.core.player.VoiceRemoval>(com.spotineil.core.player.VoiceRemoval.Off)
    override var voiceModelDownloaded = true
    override fun setKaraokeOpen(open: Boolean) { if (!open) voiceRemoval.value = com.spotineil.core.player.VoiceRemoval.Off }
    override fun setVocalsRemoved(removed: Boolean) {
        voiceRemoval.value = if (removed) com.spotineil.core.player.VoiceRemoval.Removing else com.spotineil.core.player.VoiceRemoval.Off
    }

    val commands = mutableListOf<String>()
    val playRequests = mutableListOf<PlayRequest>()
    var devicesResult: List<Device> = emptyList()
    var queueResult = PlaybackQueue(current = null, upcoming = emptyList(), isReorderable = false)

    override fun connectSpotify(context: Context) { commands += "connect" }
    override fun disconnectSpotify() { commands += "disconnect" }
    override fun play(request: PlayRequest) { playRequests += request; commands += "play" }
    override fun playLocal(tracks: List<LocalTrack>, startIndex: Int) { commands += "playLocal:$startIndex" }
    val mediaRequests = mutableListOf<List<com.spotineil.core.model.PlayableMedia>>()
    override fun playMedia(items: List<com.spotineil.core.model.PlayableMedia>, startIndex: Int, startPositionMs: Long) {
        mediaRequests += items
        commands += "playMedia:$startIndex"
    }
    override fun playNext(items: List<com.spotineil.core.model.PlayableMedia>) { commands += "playNext:${items.size}" }
    override fun addToQueueEnd(items: List<com.spotineil.core.model.PlayableMedia>) { commands += "addToQueueEnd:${items.size}" }
    override fun removeQueueItem(index: Int) { commands += "removeQueueItem:$index" }
    override val queueEdits = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    override fun togglePlayPause() { commands += "toggle" }
    override fun next() { commands += "next" }
    override fun previous() { commands += "previous" }
    override fun seekTo(positionMs: Long) { commands += "seek:$positionMs" }
    override fun toggleShuffle() { commands += "shuffle" }
    override fun cycleRepeat() { commands += "repeat" }
    override fun addToQueue(uri: String) { commands += "queue:$uri" }
    override fun setUpNext(items: List<com.spotineil.core.model.PlayableMedia>) { commands += "upnext:${items.size}" }
    override suspend fun queue(): PlaybackQueue = queueResult
    override fun moveQueueItem(from: Int, to: Int) { commands += "move:$from->$to" }
    override suspend fun devices(): List<Device> = devicesResult
    override fun transferTo(device: Device) { commands += "transfer:${device.id}" }
    override fun setSleepTimer(option: SleepTimerOption?) {
        commands += "sleep:$option"
        sleepTimer.value = when (option) {
            null -> SleepTimerState()
            SleepTimerOption.EndOfTrack -> SleepTimerState(endOfTrack = true)
            is SleepTimerOption.Minutes -> SleepTimerState(endsAtMs = option.minutes * 60_000L)
        }
    }
}
