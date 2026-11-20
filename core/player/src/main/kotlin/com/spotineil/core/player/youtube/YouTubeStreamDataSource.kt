package com.spotineil.core.player.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.spotineil.core.data.innertube.InnerTubeCatalogRepository
import com.spotineil.core.data.innertube.ResolvedYouTubeStream

@Singleton
class YouTubeStreamDataSourceFactory @Inject constructor(
    private val catalog: InnerTubeCatalogRepository,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = YouTubeStreamDataSource(catalog)
}

private class YouTubeStreamDataSource(
    private val catalog: InnerTubeCatalogRepository,
) : BaseDataSource(/* isNetwork = */ true) {
    private var streamPosition = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var directConnection: HttpURLConnection? = null
    private var directInput: java.io.InputStream? = null
    private var sabrQueue: LinkedBlockingQueue<SabrPacket>? = null
    private var sabrBuffer: ByteArray? = null
    private var sabrOffset = 0
    private var sabrScope: CoroutineScope? = null
    private var retried = false
    private var openedUri: Uri? = null
    private var openedDataSpec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        openedDataSpec = dataSpec
        val videoId = dataSpec.uri.pathSegments.lastOrNull()
            ?: throw IOException("Missing YouTube video id")
        val resolved = resolve(videoId)
        streamPosition = dataSpec.position
        retried = false

        val sabrBytes = resolved.sabrBytes(dataSpec.position)
        if (sabrBytes != null) {
            val queue = LinkedBlockingQueue<SabrPacket>(8)
            sabrQueue = queue
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            sabrScope = scope
            scope.launch {
                try {
                    sabrBytes.collect { bytes ->
                        if (bytes.isNotEmpty()) queue.put(SabrPacket.Bytes(bytes))
                    }
                    queue.put(SabrPacket.End)
                } catch (t: Throwable) {
                    if (t !is CancellationException) queue.put(SabrPacket.Error(t))
                    else queue.put(SabrPacket.End)
                }
            }
            remaining = if (dataSpec.length >= 0) dataSpec.length else
                resolved.contentLengthBytes?.takeIf { it > 0 }?.let { it - dataSpec.position }?.coerceAtLeast(0)
                    ?: C.LENGTH_UNSET.toLong()
            transferStarted(dataSpec)
            return remaining
        }

        val directUrl = resolved.directUrl ?: throw IOException("No playable audio URL")
        remaining = openDirect(directUrl, resolved.headers, dataSpec)
        transferStarted(dataSpec)
        return remaining
    }

    private fun resolve(videoId: String): ResolvedYouTubeStream =
        kotlinx.coroutines.runBlocking {
            catalog.resolveStream(videoId).getOrElse { throw IOException("Stream resolution failed", it) }
        }

    private fun openDirect(url: String, headers: Map<String, String>, dataSpec: DataSpec): Long {
        var attemptedUrl = url
        repeat(2) { attempt ->
            try {
                val connection = (URL(attemptedUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    if (dataSpec.position > 0) setRequestProperty("Range", "bytes=${dataSpec.position}-")
                }
                val code = connection.responseCode
                if (code == 403 && attempt == 0) {
                    connection.disconnect()
                    val videoId = dataSpec.uri.pathSegments.lastOrNull()
                        ?: throw IOException("Missing YouTube video id")
                    val fresh = resolve(videoId)
                    attemptedUrl = fresh.directUrl ?: throw IOException("Stream refresh returned no direct URL")
                    retried = true
                    return@repeat
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IOException("HTTP $code while opening audio")
                }
                val lengthHeader = connection.getHeaderField("Content-Length")?.toLongOrNull()
                directConnection = connection
                directInput = connection.inputStream
                val length = if (dataSpec.length >= 0) dataSpec.length else
                    if (lengthHeader != null) lengthHeader else C.LENGTH_UNSET.toLong()
                retried = true
                return length
            } catch (t: IOException) {
                if (attempt == 1) throw t
            }
        }
        throw IOException("Could not open refreshed stream")
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        directInput?.let { input ->
            try {
                val read = input.read(target, offset, length)
                if (read > 0) {
                    streamPosition += read
                    bytesTransferred(read)
                }
                return read
            } catch (t: IOException) {
                if (retried) throw t
                retried = true
                val spec = openedDataSpec ?: throw t
                val videoId = spec.uri.pathSegments.lastOrNull() ?: throw t
                val fresh = resolve(videoId)
                val url = fresh.directUrl ?: throw t
                directInput?.close()
                directConnection?.disconnect()
                val refreshedSpec = spec.buildUpon().setPosition(streamPosition).build()
                openDirect(url, fresh.headers, refreshedSpec)
                return read(target, offset, length)
            }
        }

        val queue = sabrQueue ?: return C.RESULT_END_OF_INPUT
        try {
            while (true) {
                val buffer = sabrBuffer
                if (buffer != null) {
                    val count = minOf(length, buffer.size - sabrOffset)
                    if (count > 0) {
                        buffer.copyInto(target, offset, sabrOffset, sabrOffset + count)
                        sabrOffset += count
                        if (sabrOffset >= buffer.size) {
                            sabrBuffer = null
                            sabrOffset = 0
                        }
                        streamPosition += count
                        bytesTransferred(count)
                        return count
                    }
                }

                when (val packet = queue.take()) {
                    is SabrPacket.Bytes -> {
                        sabrBuffer = packet.bytes
                        sabrOffset = 0
                    }
                    SabrPacket.End -> return C.RESULT_END_OF_INPUT
                    is SabrPacket.Error -> {
                        if (!retried) {
                            retried = true
                            val spec = openedDataSpec ?: throw IOException("SABR stream failed", packet.error)
                            val videoId = spec.uri.pathSegments.lastOrNull()
                                ?: throw IOException("SABR stream failed", packet.error)
                            val fresh = resolve(videoId)
                            val freshBytes = fresh.sabrBytes(streamPosition)
                                ?: throw IOException("SABR stream refresh failed", packet.error)
                            sabrScope?.cancel()
                            val queue = LinkedBlockingQueue<SabrPacket>(8)
                            sabrQueue = queue
                            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                            sabrScope = scope
                            scope.launch {
                                try {
                                    freshBytes.collect { bytes ->
                                        if (bytes.isNotEmpty()) queue.put(SabrPacket.Bytes(bytes))
                                    }
                                    queue.put(SabrPacket.End)
                                } catch (t: Throwable) {
                                    if (t is CancellationException) queue.put(SabrPacket.End)
                                    else queue.put(SabrPacket.Error(t))
                                }
                            }
                            continue
                        }
                        throw IOException("SABR stream failed after refresh", packet.error)
                    }
                }
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("SABR stream interrupted", t)
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        directInput?.close()
        directConnection?.disconnect()
        sabrScope?.cancel()
        directInput = null
        directConnection = null
        sabrScope = null
        sabrQueue = null
        sabrBuffer = null
        transferEnded()
    }

    private sealed interface SabrPacket {
        data class Bytes(val bytes: ByteArray) : SabrPacket
        data class Error(val error: Throwable) : SabrPacket
        data object End : SabrPacket
    }
}
