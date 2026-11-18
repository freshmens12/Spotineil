package com.spotineil.core.data.repository

import com.spotineil.core.model.Device
import com.spotineil.core.model.NowPlayingTrack
import com.spotineil.core.model.PlaybackQueue
import com.spotineil.core.model.PlaybackRestrictions
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.QueueItem
import com.spotineil.core.model.RepeatMode
import com.spotineil.core.model.bestFor
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.model.OffsetBody
import com.spotineil.core.network.model.PlayBody
import com.spotineil.core.network.model.TrackDto
import com.spotineil.core.network.model.TransferBody
import com.spotineil.core.network.model.toModel
import com.spotineil.core.network.model.toModels
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify Connect control through the Web API (`/me/player/...`). Spotify only allows these
 * commands for Premium accounts; Free accounts get a 403 PREMIUM_REQUIRED, which callers
 * surface as a friendly notice. Spotineil never tries to work around that.
 */
interface ConnectRepository {
    suspend fun devices(): List<Device>
    suspend fun transfer(deviceId: String, play: Boolean = true)
    suspend fun playbackState(): PlaybackState?
    suspend fun play(contextUri: String? = null, uris: List<String>? = null, offsetUri: String? = null, offsetIndex: Int? = null, deviceId: String? = null)
    suspend fun resume(deviceId: String? = null)
    suspend fun pause(deviceId: String? = null)
    suspend fun next(deviceId: String? = null)
    suspend fun previous(deviceId: String? = null)
    suspend fun seek(positionMs: Long, deviceId: String? = null)
    suspend fun setShuffle(enabled: Boolean, deviceId: String? = null)
    suspend fun setRepeat(mode: RepeatMode, deviceId: String? = null)
    suspend fun setVolume(percent: Int, deviceId: String? = null)
    suspend fun addToQueue(uri: String, deviceId: String? = null)
    suspend fun queue(): PlaybackQueue
}

@Singleton
class DefaultConnectRepository @Inject constructor(
    private val api: SpotifyApi,
    private val clock: Clock,
) : ConnectRepository {

    override suspend fun devices(): List<Device> = apiCall { api.devices() }.devices.map { it.toModel() }

    override suspend fun transfer(deviceId: String, play: Boolean) =
        apiCall { api.transferPlayback(TransferBody(listOf(deviceId), play)) }

    override suspend fun playbackState(): PlaybackState? {
        val response = apiCall { api.playbackState() }
        val dto = response.body() ?: return null // 204: nothing playing
        val item = dto.item
        return PlaybackState(
            source = PlaybackSource.SpotifyConnect,
            track = item?.toNowPlaying(),
            isPlaying = dto.isPlaying,
            positionMs = dto.progressMs ?: 0,
            positionTimestamp = clock.now(),
            shuffle = dto.shuffleState,
            repeat = when (dto.repeatState) { "track" -> RepeatMode.One; "context" -> RepeatMode.All; else -> RepeatMode.Off },
            restrictions = PlaybackRestrictions(),
            deviceName = dto.device?.name,
        )
    }

    override suspend fun play(contextUri: String?, uris: List<String>?, offsetUri: String?, offsetIndex: Int?, deviceId: String?) {
        val offset = when {
            offsetUri != null -> OffsetBody(uri = offsetUri)
            offsetIndex != null -> OffsetBody(position = offsetIndex)
            else -> null
        }
        apiCall { api.play(deviceId, PlayBody(contextUri = contextUri, uris = uris, offset = offset)) }
    }

    override suspend fun resume(deviceId: String?) = apiCall { api.play(deviceId, null) }
    override suspend fun pause(deviceId: String?) = apiCall { api.pause(deviceId) }
    override suspend fun next(deviceId: String?) = apiCall { api.next(deviceId) }
    override suspend fun previous(deviceId: String?) = apiCall { api.previous(deviceId) }
    override suspend fun seek(positionMs: Long, deviceId: String?) = apiCall { api.seek(positionMs, deviceId) }
    override suspend fun setShuffle(enabled: Boolean, deviceId: String?) = apiCall { api.shuffle(enabled, deviceId) }
    override suspend fun setRepeat(mode: RepeatMode, deviceId: String?) = apiCall {
        api.repeat(when (mode) { RepeatMode.Off -> "off"; RepeatMode.All -> "context"; RepeatMode.One -> "track" }, deviceId)
    }
    override suspend fun setVolume(percent: Int, deviceId: String?) = apiCall { api.volume(percent.coerceIn(0, 100), deviceId) }
    override suspend fun addToQueue(uri: String, deviceId: String?) = apiCall { api.addToQueue(uri, deviceId) }

    override suspend fun queue(): PlaybackQueue {
        val dto = apiCall { api.queue() }
        return PlaybackQueue(
            current = dto.currentlyPlaying?.toQueueItem("current"),
            upcoming = dto.queue.mapIndexed { i, t -> t.toQueueItem("q$i") },
            isReorderable = false,
        )
    }
}

internal fun TrackDto.toNowPlaying(): NowPlayingTrack {
    val track = toModel()
    return NowPlayingTrack(
        uri = track.uri,
        name = track.name,
        artists = track.artistNames,
        artistUri = track.artists.firstOrNull()?.uri,
        album = track.album?.name.orEmpty(),
        albumUri = track.album?.uri,
        durationMs = track.durationMs,
        imageUrl = (track.album?.images ?: images.toModels()).bestFor(640),
        isEpisode = type == "episode",
    )
}

private fun TrackDto.toQueueItem(key: String): QueueItem {
    val t = toModel()
    return QueueItem(key = "$key:${t.uri}", uri = t.uri, name = t.name, artists = t.artistNames, imageUrl = t.images.bestFor(160))
}
