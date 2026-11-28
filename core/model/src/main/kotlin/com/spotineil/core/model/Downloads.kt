package com.spotineil.core.model

import kotlinx.serialization.Serializable

/** One song in the Downloads list: waiting, downloading, saved, or failed. */
@Serializable
data class DownloadTask(
    /** The YouTube video id. */
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.Queued,
    /** Bytes of the stream received so far (kept when paused or offline, to continue from there). */
    val bytesDone: Long = 0,
    /** The stream's full size, once known. */
    val totalBytes: Long? = null,
    /** The saved file's name, once saved. */
    val fileName: String? = null,
    val addedAt: Long = 0,
    /** The album or playlist folder it goes into (with the others of it), or null for a single song. */
    val folder: String? = null,
    /** Its place in that album or playlist (1, 2, …): the file name starts with it. */
    val track: Int? = null,
    /** Current download speed while downloading (not saved). */
    @kotlinx.serialization.Transient val bytesPerSecond: Long = 0,
) {
    /** 0..1 while the size is known, else null. */
    val progress: Float?
        get() = when (status) {
            DownloadStatus.Done, DownloadStatus.Converting -> 1f
            else -> totalBytes?.takeIf { it > 0 }?.let { (bytesDone.toFloat() / it).coerceIn(0f, 1f) }
        }

    val isActive: Boolean
        get() = status == DownloadStatus.Queued || status == DownloadStatus.Downloading ||
            status == DownloadStatus.Converting || status == DownloadStatus.WaitingForNetwork
}

@Serializable
enum class DownloadStatus {
    /** Waiting for a free slot. */
    Queued,
    Downloading,
    /** The connection dropped: continues by itself when the internet is back. */
    WaitingForNetwork,
    /** Paused by you. */
    Paused,
    /** Downloaded; being turned into MP3/M4A and saved. */
    Converting,
    Done,
    Failed,
}
