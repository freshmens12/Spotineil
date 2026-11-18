package com.spotineil.core.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.model.StreamTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves a free track to Music/Spotineil — **only** when the artist or licence explicitly allows
 * it (Audius `is_downloadable`, Jamendo `audiodownload_allowed`). Spotify content and previews
 * are never downloadable. Saved files show up under Library → Local files.
 */
interface DownloadRepository {
    /** Returns false if the track isn't downloadable. */
    fun download(track: StreamTrack): Boolean
}

@Singleton
class SystemDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: SpotifyAppConfig,
) : DownloadRepository {

    override fun download(track: StreamTrack): Boolean {
        val url = track.downloadUrl ?: return false
        if (track.isPreview) return false
        val fileName = track.downloadFileName ?: safeFileName("${track.artist} - ${track.title}.mp3")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(track.title)
            .setDescription("${track.artist} • ${track.source.label}")
            .addRequestHeader("User-Agent", "Spotineil/${config.appVersion} (Android)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Spotineil/$fileName")
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        return runCatching { manager.enqueue(request); true }.getOrDefault(false)
    }
}
