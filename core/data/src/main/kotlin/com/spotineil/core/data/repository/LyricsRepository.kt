package com.spotineil.core.data.repository

import com.spotineil.core.data.db.LyricsDao
import com.spotineil.core.data.db.LyricsEntity
import com.spotineil.core.data.lyrics.LrcParser
import com.spotineil.core.model.Lyrics
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.api.LrclibApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.model.LrclibTrackDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

data class LyricsQuery(
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationMs: Long,
) {
    val cacheKey: String
        get() = listOf(trackName, artistName, albumName.orEmpty(), (durationMs / 1000).toString())
            .joinToString("|") { it.trim().lowercase() }
}

interface LyricsRepository {
    /** Returns lyrics or `null` when LRCLIB has nothing for this track. */
    suspend fun lyrics(query: LyricsQuery): Lyrics?
}

/**
 * Lyrics from LRCLIB (https://lrclib.net), matched by track, artist, album and duration.
 * Falls back to /search with a ±[DURATION_TOLERANCE_S] duration match. Results, including
 * "not found", are cached in Room.
 */
@Singleton
class DefaultLyricsRepository @Inject constructor(
    private val api: LrclibApi,
    private val dao: LyricsDao,
    private val clock: Clock,
) : LyricsRepository {

    override suspend fun lyrics(query: LyricsQuery): Lyrics? {
        dao.get(query.cacheKey)?.let { cached ->
            val fresh = !cached.notFound || clock.now() - cached.updatedAt < NOT_FOUND_TTL_MS
            if (fresh) {
                return if (cached.notFound) null else LrcParser.toLyrics(cached.synced, cached.plain, cached.instrumental)
            }
        }
        val match = exactMatch(query) ?: searchMatch(query)
        dao.upsert(
            LyricsEntity(
                trackKey = query.cacheKey,
                synced = match?.syncedLyrics,
                plain = match?.plainLyrics,
                instrumental = match?.instrumental ?: false,
                notFound = match == null,
                updatedAt = clock.now(),
            ),
        )
        return match?.let { LrcParser.toLyrics(it.syncedLyrics, it.plainLyrics, it.instrumental) }
    }

    private suspend fun exactMatch(q: LyricsQuery): LrclibTrackDto? = try {
        apiCall {
            api.get(
                trackName = q.trackName,
                artistName = q.artistName,
                albumName = q.albumName?.takeIf { it.isNotBlank() },
                durationSeconds = (q.durationMs / 1000.0).roundToLong().takeIf { it > 0 },
            )
        }.takeIf { it.hasContent() }
    } catch (e: SpotineilException.NotFound) {
        null
    }

    private suspend fun searchMatch(q: LyricsQuery): LrclibTrackDto? {
        val candidates = try {
            apiCall { api.search(trackName = q.trackName, artistName = q.artistName) }
        } catch (e: SpotineilException.NotFound) {
            emptyList()
        }
        val targetS = q.durationMs / 1000.0
        return candidates
            .filter { it.hasContent() }
            .filter { c -> val d = c.duration; d == null || targetS <= 0 || abs(d - targetS) <= DURATION_TOLERANCE_S }
            .sortedWith(compareByDescending<LrclibTrackDto> { !it.syncedLyrics.isNullOrBlank() }
                .thenBy { abs((it.duration ?: targetS) - targetS) })
            .firstOrNull()
    }

    private fun LrclibTrackDto.hasContent() = instrumental || !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()

    private companion object {
        const val DURATION_TOLERANCE_S = 2.0
        const val NOT_FOUND_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
