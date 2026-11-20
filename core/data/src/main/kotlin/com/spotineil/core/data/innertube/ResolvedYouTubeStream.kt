package com.spotineil.core.data.innertube

import kotlinx.coroutines.flow.Flow

data class ResolvedYouTubeStream(
    val videoId: String,
    val directUrl: String?,
    val headers: Map<String, String>,
    val expiresAtEpochMillis: Long?,
    val contentLengthBytes: Long?,
    val sabrBytes: (initialPositionMs: Long) -> Flow<ByteArray>?,
)
