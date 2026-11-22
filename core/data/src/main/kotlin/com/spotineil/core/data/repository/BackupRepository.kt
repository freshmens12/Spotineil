package com.spotineil.core.data.repository

import android.content.Context
import android.net.Uri
import com.spotineil.core.data.db.FavoriteStationDao
import com.spotineil.core.data.db.FavoriteStationEntity
import com.spotineil.core.data.db.FollowedPodcastDao
import com.spotineil.core.data.db.FollowedPodcastEntity
import com.spotineil.core.data.db.LikedMediaDao
import com.spotineil.core.data.db.LikedMediaEntity
import com.spotineil.core.data.db.RecentItemDao
import com.spotineil.core.data.db.RecentItemEntity
import com.spotineil.core.data.db.RecentSearchDao
import com.spotineil.core.data.db.RecentSearchEntity
import com.spotineil.core.data.db.UserPlaylistDao
import com.spotineil.core.data.db.UserPlaylistEntity
import com.spotineil.core.data.db.UserPlaylistItemEntity
import com.spotineil.core.model.AccentColor
import com.spotineil.core.model.AppLanguage
import com.spotineil.core.model.ThemeMode
import com.spotineil.core.model.TransitionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything in the backup file. Each list keeps the stored JSON of its rows as is. */
@Serializable
data class SpotineilBackup(
    val format: Int = 1,
    val appVersion: String = "",
    val createdAt: Long = 0,
    val liked: List<Row> = emptyList(),
    val history: List<Row> = emptyList(),
    val stations: List<Row> = emptyList(),
    val podcasts: List<Row> = emptyList(),
    val searches: List<Row> = emptyList(),
    val playlists: List<PlaylistRow> = emptyList(),
    val playlistItems: List<PlaylistItemRow> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    @Serializable data class Row(val key: String, val json: String = "", val time: Long)
    @Serializable data class PlaylistRow(val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val source: String? = null)
    @Serializable data class PlaylistItemRow(val playlistId: String, val mediaId: String, val position: Int, val json: String, val addedAt: Long)
}

/** What a restore brought back, for the message shown afterwards. */
data class RestoreSummary(val liked: Int, val playlists: Int, val history: Int)

/**
 * Backup & Restore: writes Liked Songs, playlists, history, saved stations, followed podcasts,
 * recent searches and settings to one JSON file the person picks, and reads it back on any phone.
 * Restoring adds to what is there (nothing is deleted).
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liked: LikedMediaDao,
    private val recents: RecentItemDao,
    private val stations: FavoriteStationDao,
    private val podcasts: FollowedPodcastDao,
    private val searches: RecentSearchDao,
    private val playlists: UserPlaylistDao,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    suspend fun export(target: Uri, appVersion: String) = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        val backup = SpotineilBackup(
            appVersion = appVersion,
            createdAt = System.currentTimeMillis(),
            liked = liked.observe().first().map { SpotineilBackup.Row(it.mediaId, it.json, it.addedAt) },
            history = recents.observe(Int.MAX_VALUE).first().map { SpotineilBackup.Row(it.key, it.json, it.playedAt) },
            stations = stations.observe().first().map { SpotineilBackup.Row(it.id, it.json, it.addedAt) },
            podcasts = podcasts.observe().first().map { SpotineilBackup.Row(it.feedUrl, it.json, it.followedAt) },
            searches = searches.observe(Int.MAX_VALUE).first().map { SpotineilBackup.Row(it.query, "", it.timestamp) },
            playlists = playlists.allPlaylists().map { SpotineilBackup.PlaylistRow(it.id, it.name, it.createdAt, it.updatedAt, it.source) },
            playlistItems = playlists.allItems().map { SpotineilBackup.PlaylistItemRow(it.playlistId, it.mediaId, it.position, it.json, it.addedAt) },
            settings = mapOf(
                "theme" to s.themeMode.name,
                "accent" to s.accent.name,
                "language" to s.language.name,
                "dataSaver" to s.dataSaver.toString(),
                "transition" to s.transition.name,
            ),
        )
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Cannot write the file")
        out.use { it.write(json.encodeToString(SpotineilBackup.serializer(), backup).encodeToByteArray()) }
    }

    suspend fun restore(source: Uri): RestoreSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
            ?: error("Cannot read the file")
        val b = json.decodeFromString(SpotineilBackup.serializer(), text)
        b.liked.forEach { liked.upsert(LikedMediaEntity(it.key, it.json, it.time)) }
        b.history.forEach { recents.upsert(RecentItemEntity(it.key, it.json, it.time)) }
        b.stations.forEach { stations.upsert(FavoriteStationEntity(it.key, it.json, it.time)) }
        b.podcasts.forEach { podcasts.upsert(FollowedPodcastEntity(it.key, it.json, it.time)) }
        b.searches.forEach { searches.upsert(RecentSearchEntity(it.key, it.time)) }
        b.playlists.forEach { playlists.upsertPlaylist(UserPlaylistEntity(it.id, it.name, it.createdAt, it.updatedAt, it.source)) }
        if (b.playlistItems.isNotEmpty()) {
            playlists.upsertItems(b.playlistItems.map { UserPlaylistItemEntity(it.playlistId, it.mediaId, it.position, it.json, it.addedAt) })
        }
        b.settings["theme"]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } }?.let { settings.setThemeMode(it) }
        b.settings["accent"]?.let { v -> AccentColor.entries.firstOrNull { it.name == v } }?.let { settings.setAccent(it) }
        b.settings["language"]?.let { v -> AppLanguage.entries.firstOrNull { it.name == v } }?.let { settings.setLanguage(it) }
        b.settings["dataSaver"]?.toBooleanStrictOrNull()?.let { settings.setDataSaver(it) }
        b.settings["transition"]?.let { v -> TransitionMode.entries.firstOrNull { it.name == v } }?.let { settings.setTransition(it) }
        RestoreSummary(liked = b.liked.size, playlists = b.playlists.size, history = b.history.size)
    }
}
