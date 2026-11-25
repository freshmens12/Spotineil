package com.spotineil.core.player.youtube

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.model.DownloadFormat
import com.spotineil.core.model.DownloadStatus
import com.spotineil.core.model.DownloadTask
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.YOUTUBE_MEDIA_PREFIX
import com.spotineil.core.player.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Downloads YouTube songs into Music/Spotineil, where any music player (and Library → Downloads)
 * finds them.
 *
 * Songs wait in a list ([tasks], kept on the phone) and up to the "at the same time" setting download
 * together. The stream is fetched in 1 MB pieces into a partial file, so a download that stops (no
 * internet, paused, app closed) continues from where it was: by itself when the connection is back
 * or the app opens again, or with Resume. Then it is saved in the chosen format: as it is
 * (Opus → .opus, AAC → .m4a, no re-encoding), or converted to MP3 or M4A.
 */
@Singleton
class YouTubeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streams: YouTubeStreamDataSourceFactory,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val dir = File(context.filesDir, "downloads")
    private val storeFile = File(dir, "tasks.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** Every download, newest first. */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var maxParallel = 3
    @Volatile private var format = DownloadFormat.Automatic
    @Volatile private var started = false
    /** Songs saved since the list last went quiet (for the "finished" notification). */
    @Volatile private var savedInRun = 0

    /** Loads the list and carries on with unfinished downloads. Called once when the app starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            dir.mkdirs()
            val saved = runCatching { json.decodeFromString<List<DownloadTask>>(storeFile.readText()) }.getOrDefault(emptyList())
            val loaded = saved.map {
                // Anything that was running when the app closed carries on.
                if (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Converting) it.copy(status = DownloadStatus.Queued) else it
            }
            // (Songs added in the meantime stay.)
            update { current -> current + loaded.filter { l -> current.none { it.id == l.id } } }
            settings.settings.map { it.downloads }.distinctUntilChanged().collect {
                maxParallel = it.maxParallel
                format = it.format
                schedule()
            }
        }
        watchNetwork()
    }

    /** The song comes from YouTube (and isn't a live stream), so it can be saved. */
    fun canDownload(media: PlayableMedia): Boolean = !media.isLive && videoId(media) != null

    /** Adds [media] to the downloads. False when it can't be saved or is already on its way. */
    fun download(media: PlayableMedia): Boolean {
        start()
        val id = videoId(media) ?: return false
        if (media.isLive) return false
        val existing = _tasks.value.firstOrNull { it.id == id }
        if (existing != null && (existing.isActive || existing.status == DownloadStatus.Paused)) {
            toast(context.getString(R.string.yt_download_already, media.title.ifBlank { id }))
            return false
        }
        partFile(id).delete()
        val task = DownloadTask(
            id = id,
            title = media.title.ifBlank { id },
            artist = media.artist,
            artworkUrl = media.artworkUrl,
            addedAt = System.currentTimeMillis(),
        )
        update { list -> listOf(task) + list.filter { it.id != id } }
        toast(context.getString(R.string.yt_download_started, task.title))
        schedule()
        return true
    }

    /** Stops a download for now; [resume] continues it from where it was. */
    fun pause(id: String) {
        val task = find(id) ?: return
        if (!task.isActive) return
        updateTask(id) { it.copy(status = DownloadStatus.Paused) }
        jobs[id]?.cancel()
    }

    /** Continues a paused, failed or waiting download. */
    fun resume(id: String) {
        val task = find(id) ?: return
        if (task.status == DownloadStatus.Paused || task.status == DownloadStatus.Failed || task.status == DownloadStatus.WaitingForNetwork) {
            updateTask(id) { it.copy(status = DownloadStatus.Queued) }
            schedule()
        }
    }

    /** Takes a song off the list (stopping it if needed). A saved file stays in Music/Spotineil. */
    fun remove(id: String) {
        jobs[id]?.cancel()
        update { list -> list.filter { it.id != id } }
        scope.launch { delay(500); partFile(id).delete() }
        schedule()
    }

    /** Takes the saved songs off the list (their files stay). */
    fun clearFinished() {
        update { list -> list.filter { it.status != DownloadStatus.Done } }
    }

    // ------------------------------------------------------------------------------- the queue

    private fun find(id: String) = _tasks.value.firstOrNull { it.id == id }

    private fun update(persist: Boolean = true, transform: (List<DownloadTask>) -> List<DownloadTask>) {
        val list = synchronized(lock) {
            transform(_tasks.value).also { _tasks.value = it }
        }
        if (persist) save(list)
        refreshNotification()
    }

    private fun updateTask(id: String, persist: Boolean = true, change: (DownloadTask) -> DownloadTask) =
        update(persist) { list -> list.map { if (it.id == id) change(it) else it } }

    private fun save(list: List<DownloadTask>) {
        synchronized(storeFile) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "tasks.json.tmp")
                tmp.writeText(json.encodeToString(list))
                if (!tmp.renameTo(storeFile)) {
                    storeFile.delete()
                    tmp.renameTo(storeFile)
                }
            }
        }
    }

    /** Starts waiting songs (oldest first) while there are free slots. */
    private fun schedule() {
        synchronized(lock) {
            val free = maxParallel - jobs.size
            if (free <= 0) return
            _tasks.value.filter { it.status == DownloadStatus.Queued && !jobs.containsKey(it.id) }
                .sortedBy { it.addedAt }
                .take(free)
                .forEach { task ->
                    _tasks.value = _tasks.value.map { if (it.id == task.id) it.copy(status = DownloadStatus.Downloading) else it }
                    val job = scope.launch { run(task.id) }
                    jobs[task.id] = job
                    job.invokeOnCompletion {
                        jobs.remove(task.id)
                        schedule()
                    }
                }
        }
        save(_tasks.value)
        refreshNotification()
    }

    private suspend fun run(id: String) {
        val task = find(id) ?: return
        val part = partFile(id)
        try {
            fetch(task, part)
            updateTask(id) { it.copy(status = DownloadStatus.Converting) }
            val name = saveFile(task, part)
            part.delete()
            updateTask(id) { it.copy(status = DownloadStatus.Done, fileName = name) }
            savedInRun++
            onSaved(name)
        } catch (e: CancellationException) {
            throw e // paused or removed
        } catch (e: Throwable) {
            val status = if (e !is ConversionException && !isOnline()) DownloadStatus.WaitingForNetwork else DownloadStatus.Failed
            if (find(id)?.status != DownloadStatus.Paused) updateTask(id) { it.copy(status = status) }
        }
    }

    // ------------------------------------------------------------------------------- download

    /** Fetches the stream into [part], continuing after what is already there. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun fetch(task: DownloadTask, part: File) {
        val source = streams.createDownloadSource()
        val uri = Uri.parse("ytmusic://video/${task.id}")
        var total = task.totalBytes
        dir.mkdirs()
        FileOutputStream(part, true).use { out ->
            var done = part.length()
            if (total != null && done >= total) return
            val buffer = ByteArray(64 * 1024)
            var failures = 0
            var lastReport = done
            // Speed: bytes over the last second or so, smoothed so the number doesn't jump around.
            var speed = 0L
            var windowBytes = done
            var windowStart = System.nanoTime()
            while (total == null || done < total) {
                coroutineContext.ensureActive()
                var piece = 0L
                var whole = false
                try {
                    source.open(DataSpec.Builder().setUri(uri).setPosition(done).setLength(CHUNK_BYTES).build())
                    val size = source.totalLength
                    if (size == null) {
                        // A stream that can't be fetched in pieces: it comes whole, from the start.
                        whole = true
                        if (done > 0) {
                            source.close()
                            out.channel.truncate(0)
                            done = 0
                            source.open(DataSpec.Builder().setUri(uri).build())
                        }
                    } else if (total != null && size != total) {
                        // YouTube gave a different version of the song: start that one from the top.
                        source.close()
                        out.channel.truncate(0)
                        done = 0
                        total = size
                        updateTask(task.id) { it.copy(bytesDone = 0, totalBytes = size) }
                        continue
                    } else if (total == null) {
                        total = size
                        updateTask(task.id) { it.copy(totalBytes = size) }
                    }
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(buffer, 0, buffer.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n <= 0) continue
                        out.write(buffer, 0, n)
                        piece += n
                        done += n
                        if (done - lastReport >= 128 * 1024) {
                            lastReport = done
                            val now = System.nanoTime()
                            val elapsed = now - windowStart
                            if (elapsed >= 1_000_000_000L) {
                                val measured = (done - windowBytes) * 1_000_000_000L / elapsed
                                speed = if (speed == 0L) measured else (speed * 6 + measured * 4) / 10
                                windowBytes = done
                                windowStart = now
                            }
                            val d = done
                            val v = speed
                            updateTask(task.id, persist = false) { it.copy(bytesDone = d, bytesPerSecond = v) }
                        }
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    val d = done
                    updateTask(task.id) { it.copy(bytesDone = d) }
                    if (!isOnline()) throw e
                    failures++
                    if (failures > 3) throw e
                    delay(2_000L * failures) // a hiccup: try this piece again
                    continue
                } finally {
                    runCatching { source.close() }
                }
                val d = done
                updateTask(task.id) { it.copy(bytesDone = d) }
                if (whole || piece == 0L) break
            }
        }
    }

    // ------------------------------------------------------------------------------- saving

    private class ConversionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Saves the finished stream in the chosen format; returns the file name. */
    private suspend fun saveFile(task: DownloadTask, part: File): String {
        val job = coroutineContext
        val cancelled = { job.ensureActive() }
        val work = File(dir, "${task.id}.work")
        val tagged = File(dir, "${task.id}.tagged")
        try {
            val mime = AudioConvert.audioMime(part) ?: throw ConversionException("No audio in the stream")
            val base = fileBaseName(task.title, task.artist, task.id)
            val wanted = format
            val (file, extension, fileMime) = try {
                when {
                    wanted == DownloadFormat.Mp3 -> {
                        AudioConvert.toMp3(part, work, task.title, task.artist, cancelled)
                        Triple(work, "mp3", "audio/mpeg")
                    }
                    wanted == DownloadFormat.M4a && mime != MediaFormat.MIMETYPE_AUDIO_AAC -> {
                        AudioConvert.toM4a(part, work, cancelled)
                        Triple(work, "m4a", "audio/mp4")
                    }
                    // Automatic (or M4A from an AAC stream): as it is, repackaged.
                    mime == MediaFormat.MIMETYPE_AUDIO_OPUS ->
                        if (remux(part, work, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)) {
                            val withTags = runCatching { OggOpusTags.write(work, tagged, task.title, task.artist) }.getOrDefault(false)
                            Triple(if (withTags) tagged else work, "opus", "audio/ogg")
                        } else {
                            Triple(part, "webm", "audio/webm")
                        }
                    mime == MediaFormat.MIMETYPE_AUDIO_AAC ->
                        if (remux(part, work, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) Triple(work, "m4a", "audio/mp4") else Triple(part, "m4a", "audio/mp4")
                    else -> throw ConversionException("Unsupported audio: $mime")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConversionException) {
                throw e
            } catch (e: Exception) {
                throw ConversionException("Conversion failed", e)
            }
            return publish(file, "$base.$extension", fileMime, task.title, task.artist)
        } finally {
            work.delete()
            tagged.delete()
        }
    }

    /** Copies the audio samples of [src] into a new [format] container (no re-encoding). */
    private fun remux(src: File, dst: File, format: Int): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(src.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            val m = MediaMuxer(dst.path, format).also { muxer = it }
            val out = m.addTrack(trackFormat)
            m.start()
            val size = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocate(maxOf(size, 256 * 1024))
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                info.set(0, n, extractor.sampleTime.coerceAtLeast(0L), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(out, buffer, info)
                samples++
                extractor.advance()
            }
            m.stop()
            samples > 0 && dst.length() > 0
        } catch (_: Exception) {
            dst.delete()
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun publish(file: File, name: String, mime: String, title: String, artist: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Spotineil")
            if (title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, title)
            if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("Could not write the file")).use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // Files without tags (.m4a) would show the file name and "Unknown artist": set them here too.
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        if (title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, title)
                        if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
                    },
                    null, null,
                )
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
    }

    private fun partFile(id: String) = File(dir, "$id.part")

    // ------------------------------------------------------------------------------- network

    private fun connectivity() = context.getSystemService(ConnectivityManager::class.java)

    private fun isOnline(): Boolean {
        val cm = connectivity() ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** When the internet comes back, downloads that were waiting for it carry on. */
    private fun watchNetwork() {
        val cm = connectivity() ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) resumeWaiting()
                }
            })
        }
    }

    private fun resumeWaiting() {
        if (_tasks.value.none { it.status == DownloadStatus.WaitingForNetwork }) return
        update { list -> list.map { if (it.status == DownloadStatus.WaitingForNetwork) it.copy(status = DownloadStatus.Queued) else it } }
        schedule()
    }

    // ------------------------------------------------------------------------------- messages

    private fun onSaved(name: String) {
        // One message when the last download of a batch finishes (not one per song).
        if (_tasks.value.none { it.isActive }) {
            val count = savedInRun
            toast(
                if (count <= 1) context.getString(R.string.yt_download_done, name)
                else context.resources.getQuantityString(R.plurals.yt_download_done_many, count, count),
            )
        }
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Volatile private var lastNotification = 0L

    /** One notification for all downloads: how many are left and how far along they are. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun refreshNotification() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val list = _tasks.value
        val active = list.filter { it.isActive }
        if (active.isEmpty()) {
            if (lastNotification != 0L) {
                lastNotification = 0L
                manager.cancel(NOTIFICATION_ID)
                val count = savedInRun
                savedInRun = 0
                if (count > 0 && canNotify()) {
                    ensureChannel(manager)
                    manager.notify(
                        NOTIFICATION_ID,
                        NotificationCompat.Builder(context, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(context.resources.getQuantityString(R.plurals.yt_download_done_many, count, count))
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotification < 700 && lastNotification != 0L) return
        lastNotification = now
        if (!canNotify()) return
        ensureChannel(manager)
        val known = active.filter { it.totalBytes != null }
        val progress = if (known.isEmpty()) null else known.sumOf { it.bytesDone }.toFloat() / known.sumOf { it.totalBytes!! }
        val title = if (active.size == 1) active.first().title
        else context.resources.getQuantityString(R.plurals.yt_downloading_many, active.size, active.size)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(progress?.let { "${(it * 100).toInt()}%" } ?: context.getString(R.string.yt_download_channel))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
                .build(),
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.yt_download_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 0x5D001
        private const val CHUNK_BYTES = 1L shl 20

        /** The YouTube video id of [media], or null when it isn't a YouTube song. */
        fun videoId(media: PlayableMedia): String? = when {
            media.uri.startsWith("ytmusic:video:") -> media.uri.substringAfter("ytmusic:video:")
            media.mediaId.startsWith(YOUTUBE_MEDIA_PREFIX) -> media.mediaId.removePrefix(YOUTUBE_MEDIA_PREFIX)
            else -> null
        }?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

        /** "Artist - Title", without characters file names can't hold. */
        fun fileBaseName(title: String, artist: String, fallback: String): String =
            listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { fallback }
    }
}
