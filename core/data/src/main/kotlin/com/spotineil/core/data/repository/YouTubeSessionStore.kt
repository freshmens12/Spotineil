package com.spotineil.core.data.repository

import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.model.YouTubeSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Remembers the YouTube queue, the song and the position in it (Room, via [JsonCache]), so
 * listening can continue where it stopped, even after the app was closed.
 */
@Singleton
class YouTubeSessionStore @Inject constructor(private val cache: JsonCache) {

    val session: Flow<YouTubeSession?> = cache.observe(KEY, YouTubeSession.serializer())

    suspend fun load(): YouTubeSession? = cache.get(KEY, YouTubeSession.serializer())

    suspend fun save(session: YouTubeSession) {
        if (session.queue.isEmpty()) return cache.remove(KEY)
        // Keep the saved queue small: the current song and what's around it.
        val start = (session.index - KEEP_BEFORE).coerceAtLeast(0)
        val trimmed = session.queue.drop(start).take(MAX_QUEUE)
        cache.put(KEY, YouTubeSession.serializer(), session.copy(queue = trimmed, index = session.index - start))
    }

    private companion object {
        const val KEY = "yt:session"
        const val KEEP_BEFORE = 10
        const val MAX_QUEUE = 60
    }
}
