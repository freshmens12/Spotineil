package com.spotineil.core.data.spotifyweb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A playlist in the person's Spotify library. */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val imageUrl: String?,
)

/** A Spotify song: only what is needed to find and show it (audio is played from YouTube Music). */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val imageUrl: String?,
)

/** The Spotify session is gone or Spotify turned the request down. */
class SpotifyWebException(message: String, val needsReconnect: Boolean = false) : Exception(message)

/**
 * Reads the signed-in person's Spotify library (private playlists and Liked Songs).
 *
 * Spotify's public Web API (api.spotify.com/v1) refuses web-player tokens ("429 QUOTA_EXCEEDED"),
 * so this uses the same GraphQL queries as open.spotify.com itself (api-partner.spotify.com/
 * pathfinder). Each query is named by a hash found in the web player's JavaScript; the hashes are
 * read from there, kept for a few days and read again when Spotify changes them.
 */
@Singleton
class SpotifyWebRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val auth: SpotifyWebAuth,
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs = context.getSharedPreferences("spotify_web_queries", Context.MODE_PRIVATE)
    private val hashLock = Mutex()
    @Volatile private var hashes: Map<String, String> = loadSavedHashes()

    /** The account's name, to check the token belongs to an account (null for a guest). */
    suspend fun displayName(token: String): String? {
        val root = runCatching { query(OP_PROFILE, buildJsonObject { }, token) }.getOrNull() ?: return null
        return profileName(root)
    }

    suspend fun playlists(): List<SpotifyPlaylist> {
        val out = LinkedHashMap<String, SpotifyPlaylist>()
        var offset = 0
        for (page in 0 until MAX_PLAYLIST_PAGES) {
            val root = query(
                OP_LIBRARY,
                buildJsonObject {
                    put("filters", buildJsonArray { add(JsonPrimitiveString("Playlists")) })
                    put("order", JsonNull)
                    put("textFilter", JsonNull)
                    put("features", buildJsonArray { add(JsonPrimitiveString("LIKED_SONGS")); add(JsonPrimitiveString("YOUR_EPISODES_V2")) })
                    put("limit", PAGE)
                    put("offset", offset)
                    put("flatten", true)
                    put("folderUri", JsonNull)
                    put("includeFoldersWhenFlattening", true)
                },
            )
            libraryPlaylists(root).forEach { out.putIfAbsent(it.id, it) }
            val size = libraryPageSize(root)
            if (size < PAGE) break
            offset += size
        }
        return out.values.toList()
    }

    /** Liked Songs, newest first. */
    suspend fun likedSongs(): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        var offset = 0
        for (page in 0 until MAX_TRACK_PAGES) {
            val root = query(OP_LIKED, buildJsonObject { put("offset", offset); put("limit", PAGE) })
            out += likedPageTracks(root)
            val size = likedPageSize(root)
            if (size < PAGE) break
            offset += size
        }
        return out
    }

    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        var offset = 0
        for (page in 0 until MAX_TRACK_PAGES) {
            val root = query(
                OP_PLAYLIST,
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset)
                    put("limit", PLAYLIST_PAGE)
                    put("enableWatchFeedEntrypoint", false)
                },
            )
            out += playlistPageTracks(root)
            val size = playlistPageSize(root)
            if (size < PLAYLIST_PAGE) break
            offset += size
        }
        return out
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Runs one web-player query. A 401 gets one new token; an unknown query hash makes it read the
     * hashes again from the web player once.
     */
    private suspend fun query(op: String, variables: JsonObject, fixedToken: String? = null): JsonObject {
        var token = fixedToken ?: auth.accessToken() ?: throw SpotifyWebException("Spotify is not connected", needsReconnect = true)
        var refreshedHashes = false
        repeat(3) {
            val hash = hashFor(op, forceRefresh = false)
            val body = buildJsonObject {
                put("variables", variables)
                put("operationName", op)
                putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
            }.toString()
            val response = post(body, token)
            when (response.code) {
                in 200..299 -> {
                    val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
                        ?: throw SpotifyWebException("Unexpected answer from Spotify")
                    val errors = graphErrors(root)
                    if (errors.any { it.contains("PersistedQuery", ignoreCase = true) } && !refreshedHashes) {
                        refreshedHashes = true
                        hashFor(op, forceRefresh = true)
                        return@repeat
                    }
                    if (root["data"] == null && errors.isNotEmpty()) throw SpotifyWebException("Spotify: ${errors.first()}")
                    return root
                }
                401 -> {
                    if (fixedToken != null) throw SpotifyWebException("The Spotify session ended", needsReconnect = true)
                    token = auth.accessToken(forceRefresh = true)
                        ?: throw SpotifyWebException("The Spotify session ended", needsReconnect = true)
                }
                429 -> throw SpotifyWebException("Spotify says: too many requests, try again in a minute")
                else -> throw SpotifyWebException("Spotify error (HTTP ${response.code})")
            }
        }
        throw SpotifyWebException("Spotify did not answer")
    }

    private class Raw(val code: Int, val body: String)

    private suspend fun post(body: String, token: String): Raw = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(PATHFINDER)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("app-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .apply { auth.clientToken?.let { header("client-token", it) } }
            .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { Raw(it.code, it.body?.string().orEmpty()) }
        } catch (e: java.io.IOException) {
            throw SpotifyWebException("No connection to Spotify")
        }
    }

    // ---- Query hashes --------------------------------------------------------------------

    private suspend fun hashFor(op: String, forceRefresh: Boolean): String {
        val stale = System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0L) > HASHES_MAX_AGE_MS
        if (forceRefresh || (stale && hashes[op] == null)) refreshHashes()
        return hashes[op] ?: FALLBACK_HASHES[op] ?: throw SpotifyWebException("Spotify changed its web player; update Spotineil")
    }

    /** Reads every query hash from the web player's JavaScript (open.spotify.com → web-player.*.js). */
    private suspend fun refreshHashes() = hashLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val page = get("https://open.spotify.com/")
                val scripts = Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/web-player\\.[0-9a-f]+\\.js").findAll(page).map { it.value }.distinct().toList()
                val found = scripts.flatMap { operationHashes(get(it)).entries }.associate { it.key to it.value }
                if (found.isNotEmpty()) {
                    hashes = found.filterKeys { it in WANTED }
                    prefs.edit()
                        .putString(KEY_HASHES, json.encodeToString(HashMapSerializer, hashes))
                        .putLong(KEY_TIME, System.currentTimeMillis())
                        .apply()
                }
            }
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
        return http.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    private fun loadSavedHashes(): Map<String, String> = runCatching {
        val saved = prefs.getString(KEY_HASHES, null) ?: return emptyMap()
        json.decodeFromString(HashMapSerializer, saved)
    }.getOrDefault(emptyMap())

    @Suppress("FunctionName")
    private fun JsonPrimitiveString(s: String): JsonElement = kotlinx.serialization.json.JsonPrimitive(s)

    private companion object {
        val HashMapSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
        const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val OP_PROFILE = "profileAttributes"
        const val OP_LIBRARY = "libraryV3"
        const val OP_LIKED = "fetchLibraryTracks"
        const val OP_PLAYLIST = "fetchPlaylist"
        val WANTED = setOf(OP_PROFILE, OP_LIBRARY, OP_LIKED, OP_PLAYLIST)

        /** Hashes as of November 2026, used until fresh ones have been read. */
        val FALLBACK_HASHES = mapOf(
            OP_PROFILE to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
            OP_LIBRARY to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            OP_LIKED to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            OP_PLAYLIST to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        )

        const val KEY_HASHES = "hashes"
        const val KEY_TIME = "hashes_time"
        const val HASHES_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val PAGE = 50
        const val PLAYLIST_PAGE = 100
        const val MAX_PLAYLIST_PAGES = 10
        const val MAX_TRACK_PAGES = 20
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
