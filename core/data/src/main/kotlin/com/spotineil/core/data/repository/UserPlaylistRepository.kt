package com.spotineil.core.data.repository

import com.spotineil.core.data.db.UserPlaylistDao
import com.spotineil.core.data.db.UserPlaylistEntity
import com.spotineil.core.data.db.UserPlaylistItemEntity
import com.spotineil.core.model.PlayableMedia
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** A playlist in "Your playlists". */
data class UserPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
    val coverUrl: String?,
    /** "spotify:<id>" when it was saved from a Spotify playlist. */
    val source: String?,
)

/** Playlists made on this phone: create, rename, delete, add/remove/reorder songs. Stored in Room. */
@Singleton
class UserPlaylistRepository @Inject constructor(
    private val dao: UserPlaylistDao,
    private val json: Json,
) {
    val playlists: Flow<List<UserPlaylist>> = dao.observeSummaries().map { rows ->
        rows.map { r ->
            UserPlaylist(r.id, r.name, r.count, r.firstJson?.let(::decode)?.artworkUrl, r.source)
        }
    }

    fun playlist(id: String): Flow<UserPlaylist?> = dao.observePlaylist(id).map { p ->
        p?.let { UserPlaylist(it.id, it.name, 0, null, it.source) }
    }

    fun songs(id: String): Flow<List<PlayableMedia>> = dao.observeItems(id).map { rows -> rows.mapNotNull { decode(it.json) } }

    suspend fun create(name: String, songs: List<PlayableMedia> = emptyList(), source: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsertPlaylist(UserPlaylistEntity(id, name.trim().ifEmpty { "My playlist" }, now, now, source))
        if (songs.isNotEmpty()) replaceSongs(id, songs)
        return id
    }

    /** The playlist saved earlier from [source] (e.g. a Spotify playlist), if any. */
    suspend fun findBySource(source: String): String? = dao.bySource(source)?.id

    suspend fun rename(id: String, name: String) = dao.rename(id, name.trim().ifEmpty { "My playlist" }, System.currentTimeMillis())

    suspend fun delete(id: String) {
        dao.clearItems(id)
        dao.deletePlaylist(id)
    }

    /** Adds [songs] at the end; songs already in the playlist are skipped. Returns how many were added. */
    suspend fun add(id: String, songs: List<PlayableMedia>): Int {
        val existing = dao.items(id).mapTo(HashSet()) { it.mediaId }
        var pos = dao.lastPosition(id)
        val now = System.currentTimeMillis()
        val fresh = songs.distinctBy { it.mediaId }.filter { it.mediaId !in existing }
            .map { UserPlaylistItemEntity(id, it.mediaId, ++pos, encode(it), now) }
        if (fresh.isNotEmpty()) {
            dao.upsertItems(fresh)
            dao.touch(id, now)
        }
        return fresh.size
    }

    suspend fun remove(id: String, mediaId: String) {
        dao.removeItem(id, mediaId)
        dao.touch(id, System.currentTimeMillis())
    }

    /** Moves the song at [from] to [to] (indices in the playlist's order). */
    suspend fun move(id: String, from: Int, to: Int) {
        val items = dao.items(id).toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        dao.upsertItems(items.mapIndexed { i, it -> it.copy(position = i) })
    }

    /** Replaces all songs (used when a Spotify playlist is saved again). */
    suspend fun replaceSongs(id: String, songs: List<PlayableMedia>) {
        val now = System.currentTimeMillis()
        dao.clearItems(id)
        dao.upsertItems(songs.distinctBy { it.mediaId }.mapIndexed { i, m -> UserPlaylistItemEntity(id, m.mediaId, i, encode(m), now) })
        dao.touch(id, now)
    }

    private fun encode(m: PlayableMedia) = json.encodeToString(PlayableMedia.serializer(), m)
    private fun decode(s: String): PlayableMedia? = runCatching { json.decodeFromString(PlayableMedia.serializer(), s) }.getOrNull()
}
