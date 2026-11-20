package com.spotineil.core.player.local

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spotineil.core.model.LocalTrack
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.toPlayable

internal const val LOCAL_ID_PREFIX = "local:"
internal const val ROOT_ID = "root"
internal const val SONGS_ID = "songs"

/** Extra key marking live streams (radio), which can't be seeked. */
internal const val EXTRA_IS_LIVE = "spotineil.is_live"
internal const val EXTRA_IS_PREVIEW = "spotineil.is_preview"
internal const val EXTRA_IS_EPISODE = "spotineil.is_episode"

/** Full song length (differs from the media length for 30 s previews). */
internal const val EXTRA_DURATION_MS = "spotineil.duration_ms"

internal fun LocalTrack.toMediaItem(): MediaItem = toPlayable().toMediaItem()

internal fun PlayableMedia.toMediaItem(): MediaItem {
    val uri = if (uri.startsWith("ytmusic:video:")) {
        Uri.parse("ytmusic://video/${uri.substringAfter("ytmusic:video:")}")
    } else {
        Uri.parse(uri)
    }
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        // Keeps the URI available even when the item is sent over a session boundary
        // (Android Auto, external controllers), where localConfiguration is stripped.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(
                    when {
                        isLive -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                        isEpisode -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        else -> MediaMetadata.MEDIA_TYPE_MUSIC
                    },
                )
                .setExtras(
                    android.os.Bundle().apply {
                        putBoolean(EXTRA_IS_LIVE, isLive)
                        putBoolean(EXTRA_IS_PREVIEW, isPreview)
                        putBoolean(EXTRA_IS_EPISODE, isEpisode)
                        putLong(EXTRA_DURATION_MS, durationMs)
                    },
                )
                .build(),
        )
        .build()
}

internal fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build(),
    )
    .build()
