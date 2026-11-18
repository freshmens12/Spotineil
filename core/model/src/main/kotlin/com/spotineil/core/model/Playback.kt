package com.spotineil.core.model

enum class RepeatMode { Off, All, One;
    fun next(): RepeatMode = when (this) { Off -> All; All -> One; One -> Off }
}

/** Which engine is producing audio. */
enum class PlaybackSource { None, SpotifyAppRemote, SpotifyConnect, Local }

data class NowPlayingTrack(
    val uri: String,
    val name: String,
    val artists: String,
    /** First artist URI, when known (for "Go to artist"). */
    val artistUri: String?,
    val album: String,
    val albumUri: String?,
    val durationMs: Long,
    val imageUrl: String?,
    val isEpisode: Boolean = false,
    /** Set for on-device playback: what's needed to save the song to "Liked songs". */
    val media: PlayableMedia? = null,
) {
    val isPreview: Boolean get() = media?.isPreview == true
    val isLive: Boolean get() = media?.isLive == true

    /** Length used to match lyrics: the full song length, even while a 30 s preview plays. */
    val lyricsDurationMs: Long get() = media?.durationMs?.takeIf { it > 0 } ?: durationMs

    val spotifyId: String? get() = uri.takeIf { it.startsWith("spotify:") }?.substringAfterLast(':')
}

data class PlaybackRestrictions(
    val canSkipNext: Boolean = true,
    val canSkipPrevious: Boolean = true,
    val canSeek: Boolean = true,
    val canToggleShuffle: Boolean = true,
    val canRepeat: Boolean = true,
)

data class PlaybackState(
    val source: PlaybackSource = PlaybackSource.None,
    val track: NowPlayingTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** Wall-clock time [positionMs] was sampled, so UIs can interpolate progress. */
    val positionTimestamp: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val restrictions: PlaybackRestrictions = PlaybackRestrictions(),
    val deviceName: String? = null,
) {
    fun estimatedPosition(now: Long): Long {
        val duration = track?.durationMs ?: return positionMs
        if (!isPlaying || positionTimestamp == 0L) return positionMs.coerceIn(0, duration)
        return (positionMs + (now - positionTimestamp)).coerceIn(0, duration)
    }
}

/** Queue entry; [index] is stable within the snapshot. */
data class QueueItem(
    val key: String,
    val uri: String,
    val name: String,
    val artists: String,
    val imageUrl: String?,
)

data class PlaybackQueue(
    val current: QueueItem?,
    val upcoming: List<QueueItem>,
    /** Only local (Media3) queues can be reordered; Spotify's Web API queue is read-only. */
    val isReorderable: Boolean,
)

enum class RemoteConnectionState { Disconnected, Connecting, Connected, SpotifyNotInstalled, Failed }
