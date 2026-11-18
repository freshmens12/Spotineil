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
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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
}
