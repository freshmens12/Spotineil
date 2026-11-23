package com.spotineil.core.data.db

import androidx.paging.PagingSource
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(@PrimaryKey val query: String, val timestamp: Long)

@Entity(tableName = "library_items", primaryKeys = ["type", "id"])
data class LibraryItemEntity(
    val type: String,
    val id: String,
    val uri: String,
    val name: String,
    val subtitle: String,
    val creator: String,
    val imageUrl: String?,
    val addedAt: Long,
    /** Order as returned by Spotify (most recently added/played first). */
    val position: Int,
)

@Entity(tableName = "library_remote_keys")
data class LibraryRemoteKeyEntity(
    @PrimaryKey val type: String,
    /** Offset or cursor of the next page; null when the end was reached. */
    val nextKey: String?,
    val lastUpdated: Long,
)

/** Generic offline-first cache of serialized domain objects (home feed, detail pages, profile). */
@Entity(tableName = "cached_responses")
data class CachedResponseEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long)

@Entity(tableName = "lyrics_cache")
data class LyricsEntity(
    @PrimaryKey val trackKey: String,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    val notFound: Boolean,
    val updatedAt: Long,
)

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentSearchEntity>>

    @Upsert
    suspend fun upsert(entity: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches WHERE `query` NOT IN (SELECT `query` FROM recent_searches ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_searches")
    suspend fun clear()
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items WHERE type = :type ORDER BY position ASC")
    fun pagingByRecent(type: String): PagingSource<Int, LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE type = :type ORDER BY name COLLATE NOCASE ASC")
    fun pagingByName(type: String): PagingSource<Int, LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE type = :type ORDER BY creator COLLATE NOCASE ASC, name COLLATE NOCASE ASC")
    fun pagingByCreator(type: String): PagingSource<Int, LibraryItemEntity>

    @Upsert
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM library_items WHERE type = :type")
    suspend fun maxPosition(type: String): Int

    @Query("SELECT COALESCE(MIN(position), 0) FROM library_items WHERE type = :type")
    suspend fun minPosition(type: String): Int

    @Query("DELETE FROM library_items WHERE type = :type")
    suspend fun clearType(type: String)

    @Query("DELETE FROM library_items WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("UPDATE library_items SET name = :name WHERE type = :type AND id = :id")
    suspend fun rename(type: String, id: String, name: String)

    @Query("SELECT * FROM library_items WHERE type = :type ORDER BY position ASC")
    suspend fun all(type: String): List<LibraryItemEntity>
}

@Dao
interface LibraryRemoteKeyDao {
    @Query("SELECT * FROM library_remote_keys WHERE type = :type")
    suspend fun get(type: String): LibraryRemoteKeyEntity?

    @Upsert
    suspend fun upsert(key: LibraryRemoteKeyEntity)

    @Query("DELETE FROM library_remote_keys WHERE type = :type")
    suspend fun delete(type: String)
}

@Dao
interface CachedResponseDao {
    @Query("SELECT * FROM cached_responses WHERE `key` = :key")
    suspend fun get(key: String): CachedResponseEntity?

    @Query("SELECT * FROM cached_responses WHERE `key` = :key")
    fun observe(key: String): Flow<CachedResponseEntity?>

    @Upsert
    suspend fun upsert(entity: CachedResponseEntity)

    @Query("DELETE FROM cached_responses WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackKey = :key")
    suspend fun get(key: String): LyricsEntity?

    @Upsert
    suspend fun upsert(entity: LyricsEntity)
}

@Entity(tableName = "favorite_stations")
data class FavoriteStationEntity(@PrimaryKey val id: String, val json: String, val addedAt: Long)

@Dao
interface FavoriteStationDao {
    @Query("SELECT * FROM favorite_stations ORDER BY addedAt DESC")
    fun observe(): Flow<List<FavoriteStationEntity>>

    @Upsert
    suspend fun upsert(entity: FavoriteStationEntity)

    @Query("DELETE FROM favorite_stations WHERE id = :id")
    suspend fun delete(id: String)
}

/** A free/local song the listener liked ("Liked songs" without a Spotify account). [json] is a PlayableMedia. */
@Entity(tableName = "liked_media")
data class LikedMediaEntity(@PrimaryKey val mediaId: String, val json: String, val addedAt: Long)

@Dao
interface LikedMediaDao {
    @Query("SELECT * FROM liked_media ORDER BY addedAt DESC")
    fun observe(): Flow<List<LikedMediaEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_media WHERE mediaId = :id)")
    fun observeIsLiked(id: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: LikedMediaEntity)

    @Query("DELETE FROM liked_media WHERE mediaId = :id")
    suspend fun delete(id: String)
}

/** "Jump back in" history. [json] is a RecentItem. */
@Entity(tableName = "recent_items")
data class RecentItemEntity(@PrimaryKey val key: String, val json: String, val playedAt: Long)

@Dao
interface RecentItemDao {
    @Query("SELECT * FROM recent_items ORDER BY playedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentItemEntity>>

    @Upsert
    suspend fun upsert(entity: RecentItemEntity)

    /** Keeps the table small. */
    @Query("DELETE FROM recent_items WHERE `key` NOT IN (SELECT `key` FROM recent_items ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_items")
    suspend fun clear()
}

/** Podcasts the listener follows. [json] is a Podcast. */
@Entity(tableName = "followed_podcasts")
data class FollowedPodcastEntity(@PrimaryKey val feedUrl: String, val json: String, val followedAt: Long)

@Dao
interface FollowedPodcastDao {
    @Query("SELECT * FROM followed_podcasts ORDER BY followedAt DESC")
    fun observe(): Flow<List<FollowedPodcastEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_podcasts WHERE feedUrl = :feedUrl)")
    fun observeIsFollowed(feedUrl: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: FollowedPodcastEntity)

    @Query("DELETE FROM followed_podcasts WHERE feedUrl = :feedUrl")
    suspend fun delete(feedUrl: String)
}


/** A playlist made by the person (or saved from Spotify, then [source] is "spotify:<id>"). */
@Entity(tableName = "user_playlists")
data class UserPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String? = null,
)

/** One song of a playlist; [json] is a serialized PlayableMedia. */
@Entity(tableName = "user_playlist_items", primaryKeys = ["playlistId", "mediaId"])
data class UserPlaylistItemEntity(
    val playlistId: String,
    val mediaId: String,
    val position: Int,
    val json: String,
    val addedAt: Long,
)

data class UserPlaylistSummaryRow(
    val id: String,
    val name: String,
    val source: String?,
    val updatedAt: Long,
    val count: Int,
    val firstJson: String?,
)

@Dao
interface UserPlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.source, p.updatedAt,
           (SELECT COUNT(*) FROM user_playlist_items i WHERE i.playlistId = p.id) AS count,
           (SELECT i.json FROM user_playlist_items i WHERE i.playlistId = p.id ORDER BY i.position LIMIT 1) AS firstJson
           FROM user_playlists p ORDER BY p.updatedAt DESC""",
    )
    fun observeSummaries(): Flow<List<UserPlaylistSummaryRow>>

    @Query("SELECT * FROM user_playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<UserPlaylistEntity?>

    @Query("SELECT * FROM user_playlists")
    suspend fun allPlaylists(): List<UserPlaylistEntity>

    @Query("SELECT * FROM user_playlists WHERE source = :source LIMIT 1")
    suspend fun bySource(source: String): UserPlaylistEntity?

    @Query("SELECT * FROM user_playlist_items WHERE playlistId = :id ORDER BY position")
    fun observeItems(id: String): Flow<List<UserPlaylistItemEntity>>

    @Query("SELECT * FROM user_playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun items(id: String): List<UserPlaylistItemEntity>

    @Query("SELECT * FROM user_playlist_items")
    suspend fun allItems(): List<UserPlaylistItemEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM user_playlist_items WHERE playlistId = :id")
    suspend fun lastPosition(id: String): Int

    @Upsert suspend fun upsertPlaylist(entity: UserPlaylistEntity)
    @Upsert suspend fun upsertItems(items: List<UserPlaylistItemEntity>)

    @Query("UPDATE user_playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE user_playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM user_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("DELETE FROM user_playlist_items WHERE playlistId = :id")
    suspend fun clearItems(id: String)

    @Query("DELETE FROM user_playlist_items WHERE playlistId = :id AND mediaId = :mediaId")
    suspend fun removeItem(id: String, mediaId: String)
}


/** One listen: a song played for at least half a minute. [json] is a serialized PlayableMedia. */
@Entity(tableName = "play_log", indices = [androidx.room.Index("playedAt"), androidx.room.Index("mediaId")])
data class PlayLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val artist: String,
    val json: String,
    val playedAt: Long,
    val listenedMs: Long,
)

data class PlayCountRow(val mediaId: String, val json: String, val plays: Int, val listenedMs: Long, val lastPlayed: Long)
data class ArtistCountRow(val artist: String, val plays: Int, val listenedMs: Long, val json: String)

@Dao
interface PlayLogDao {
    @androidx.room.Insert suspend fun insert(entity: PlayLogEntity)

    @Query(
        """SELECT mediaId, json, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(playedAt) AS lastPlayed
           FROM play_log WHERE playedAt >= :since GROUP BY mediaId ORDER BY plays DESC, listenedMs DESC LIMIT :limit""",
    )
    suspend fun topSongs(since: Long, limit: Int): List<PlayCountRow>

    @Query(
        """SELECT artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(json) AS json
           FROM play_log WHERE playedAt >= :since AND artist != '' GROUP BY artist ORDER BY plays DESC LIMIT :limit""",
    )
    suspend fun topArtists(since: Long, limit: Int): List<ArtistCountRow>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM play_log WHERE playedAt >= :since")
    suspend fun listenedSince(since: Long): Long

    @Query("SELECT COUNT(DISTINCT mediaId) FROM play_log WHERE playedAt >= :since")
    suspend fun distinctSongsSince(since: Long): Int

    /** Songs played at least [minPlays] times before [before] but not since then. */
    @Query(
        """SELECT mediaId, json, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs, MAX(playedAt) AS lastPlayed
           FROM play_log GROUP BY mediaId HAVING MAX(playedAt) < :before AND COUNT(*) >= :minPlays
           ORDER BY plays DESC LIMIT :limit""",
    )
    suspend fun forgotten(before: Long, minPlays: Int, limit: Int): List<PlayCountRow>

    @Query("SELECT * FROM play_log")
    suspend fun all(): List<PlayLogEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<PlayLogEntity>)
}

@Database(
    entities = [
        RecentSearchEntity::class,
        LibraryItemEntity::class,
        LibraryRemoteKeyEntity::class,
        CachedResponseEntity::class,
        LyricsEntity::class,
        FavoriteStationEntity::class,
        LikedMediaEntity::class,
        RecentItemEntity::class,
        FollowedPodcastEntity::class,
        UserPlaylistEntity::class,
        UserPlaylistItemEntity::class,
        PlayLogEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class SpotineilDatabase : RoomDatabase() {
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun libraryDao(): LibraryDao
    abstract fun libraryRemoteKeyDao(): LibraryRemoteKeyDao
    abstract fun cachedResponseDao(): CachedResponseDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun favoriteStationDao(): FavoriteStationDao
    abstract fun likedMediaDao(): LikedMediaDao
    abstract fun recentItemDao(): RecentItemDao
    abstract fun followedPodcastDao(): FollowedPodcastDao
    abstract fun userPlaylistDao(): UserPlaylistDao
    abstract fun playLogDao(): PlayLogDao
}
