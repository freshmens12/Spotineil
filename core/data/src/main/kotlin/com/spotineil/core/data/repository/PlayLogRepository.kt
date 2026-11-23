package com.spotineil.core.data.repository

import com.spotineil.core.data.db.PlayCountRow
import com.spotineil.core.data.db.PlayLogDao
import com.spotineil.core.data.db.PlayLogEntity
import com.spotineil.core.model.PlayableMedia
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** A song with how often (and how long) it was played in a period. */
data class SongStat(val media: PlayableMedia, val plays: Int, val listenedMs: Long)

/** An artist with how often they were played; [artworkUrl] comes from one of their songs. */
data class ArtistStat(val name: String, val plays: Int, val listenedMs: Long, val artworkUrl: String?)

/** "Spotineil Wrapped" for a period. */
data class ListeningSummary(
    val minutes: Long,
    val songCount: Int,
    val topSongs: List<SongStat>,
    val topArtists: List<ArtistStat>,
)

/**
 * Every listen of at least [MIN_LISTEN_MS], kept on the phone. Feeds Wrapped and the
 * "made for you" shelves on Home (top artists, rediscover).
 */
@Singleton
class PlayLogRepository @Inject constructor(
    private val dao: PlayLogDao,
    private val json: Json,
) {
    /** Logs one listen of [media]; short listens (skips) are not counted. */
    suspend fun record(media: PlayableMedia, listenedMs: Long) {
        if (listenedMs < MIN_LISTEN_MS || media.isLive) return
        dao.insert(
            PlayLogEntity(
                mediaId = media.mediaId,
                artist = media.artist.substringBefore(',').trim(),
                json = json.encodeToString(PlayableMedia.serializer(), media),
                playedAt = System.currentTimeMillis(),
                listenedMs = listenedMs,
            ),
        )
    }

    suspend fun summary(sinceMs: Long): ListeningSummary = ListeningSummary(
        minutes = dao.listenedSince(sinceMs) / 60_000,
        songCount = dao.distinctSongsSince(sinceMs),
        topSongs = topSongs(sinceMs, 5),
        topArtists = topArtists(sinceMs, 5),
    )

    suspend fun topSongs(sinceMs: Long, limit: Int): List<SongStat> = dao.topSongs(sinceMs, limit).mapNotNull { it.toStat() }

    suspend fun topArtists(sinceMs: Long, limit: Int): List<ArtistStat> = dao.topArtists(sinceMs, limit).map { row ->
        ArtistStat(row.artist, row.plays, row.listenedMs, decode(row.json)?.artworkUrl)
    }

    /** Songs you played a lot but not in the last [quietDays] days. */
    suspend fun forgotten(quietDays: Int, limit: Int): List<SongStat> =
        dao.forgotten(System.currentTimeMillis() - quietDays * DAY_MS, minPlays = 2, limit = limit).mapNotNull { it.toStat() }

    private fun PlayCountRow.toStat(): SongStat? = decode(json)?.let { SongStat(it, plays, listenedMs) }

    private fun decode(s: String): PlayableMedia? = runCatching { json.decodeFromString(PlayableMedia.serializer(), s) }.getOrNull()

    companion object {
        const val MIN_LISTEN_MS = 30_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
