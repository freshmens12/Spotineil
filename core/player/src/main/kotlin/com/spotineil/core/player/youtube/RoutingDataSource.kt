package com.spotineil.core.player.youtube

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Sends `ytmusic://` URIs to [youTube] and every other URI (files, http) to [default]. */
class RoutingDataSourceFactory(
    private val default: DataSource.Factory,
    private val youTube: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(default.createDataSource(), youTube.createDataSource())
}

private class RoutingDataSource(
    private val default: DataSource,
    private val youTube: DataSource,
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        default.addTransferListener(transferListener)
        youTube.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "ytmusic") youTube else default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
