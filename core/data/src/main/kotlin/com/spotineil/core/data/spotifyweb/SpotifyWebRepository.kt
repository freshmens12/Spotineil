package com.spotineil.core.data.spotifyweb

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * Reads the signed-in person's Spotify library (private playlists and Liked Songs) with the web
 * player's access token from [SpotifyWebAuth].
 */
@Singleton
class SpotifyWebRepository @Inject constructor(
    private val auth: SpotifyWebAuth,
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Asks Spotify who is signed in, to check the sign-in really is an account (not a guest). */
    suspend fun displayName(token: String): String? {
        val body = get("$API/me", token) ?: return null
        val obj = parse(body) as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        return obj.string("display_name")?.takeIf { it.isNotBlank() } ?: id
    }

    suspend fun playlists(): List<SpotifyPlaylist> {
        val out = mutableListOf<SpotifyPlaylist>()
        pages("$API/me/playlists?limit=50", MAX_PLAYLIST_PAGES) { items -> items.forEach { playlistFrom(it)?.let(out::add) } }
        return out
    }

    /** Liked Songs, newest first (up to [MAX_TRACK_PAGES] x 50). */
    suspend fun likedSongs(): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        pages("$API/me/tracks?limit=50", MAX_TRACK_PAGES) { items -> items.forEach { trackFromEntry(it)?.let(out::add) } }
        return out
    }

    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> {
        val out = mutableListOf<SpotifyTrack>()
        val collect: (JsonArray) -> Unit = { items ->
            items.forEach { trackFromEntry(it)?.let(out::add) }
        }
        try {
            pages("$API/playlists/$playlistId/tracks?limit=100", MAX_TRACK_PAGES, collect)
        } catch (e: SpotifyWebException) {
            if (e.needsReconnect || out.isNotEmpty()) throw e
            out.clear()
            pages("$API/playlists/$playlistId/items?limit=100", MAX_TRACK_PAGES, collect)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun pages(firstUrl: String, maxPages: Int, onItems: (JsonArray) -> Unit) {
        var url: String? = firstUrl
        var page = 0
        while (url != null && page < maxPages) {
            val body = authorizedGet(url)
            val root = parse(body) as? JsonObject ?: throw SpotifyWebException("Unexpected answer from Spotify")
            (root["items"] as? JsonArray)?.let(onItems)
            url = root.string("next")
            page++
        }
    }

    /** GET with the saved token; a 401 gets one new token (the session may have just expired). */
    private suspend fun authorizedGet(url: String): String {
        var token = auth.accessToken() ?: throw SpotifyWebException("Spotify is not connected", needsReconnect = true)
        repeat(2) { attempt ->
            val response = rawGet(url, token)
            when (response.code) {
                in 200..299 -> return response.body
                401 -> {
                    if (attempt == 1) throw SpotifyWebException("The Spotify session ended", needsReconnect = true)
                    token = auth.accessToken(forceRefresh = true)
                        ?: throw SpotifyWebException("The Spotify session ended", needsReconnect = true)
                }
                403 -> throw SpotifyWebException("Spotify did not allow this (HTTP 403)")
                404 -> throw SpotifyWebException("Not found on Spotify")
                429 -> throw SpotifyWebException("Spotify says: too many requests, try again in a minute")
                else -> throw SpotifyWebException("Spotify error (HTTP ${response.code})")
            }
        }
        throw SpotifyWebException("Spotify did not answer")
    }

    private suspend fun get(url: String, token: String): String? = rawGet(url, token).takeIf { it.code in 200..299 }?.body

    private class Raw(val code: Int, val body: String)

    private suspend fun rawGet(url: String, token: String): Raw = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(request).execute().use { Raw(it.code, it.body?.string().orEmpty()) }
        } catch (e: java.io.IOException) {
            throw SpotifyWebException("No connection to Spotify")
        }
    }

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val API = "https://api.spotify.com/v1"
        const val MAX_PLAYLIST_PAGES = 10
        const val MAX_TRACK_PAGES = 20
    }
}
