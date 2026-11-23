package com.spotineil.core.data.spotifyweb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Readers for the answers of Spotify's web-player GraphQL API (api-partner.spotify.com/pathfinder).
// They look fields up by name and tolerate missing ones, because Spotify changes these shapes.

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.path(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}

/** The best cover from a `{"sources":[{url,width}]}` object: about 300 px wide when there is one. */
internal fun coverFrom(art: JsonElement?): String? {
    val sources = (art.obj()?.get("sources") as? JsonArray).orEmpty().mapNotNull { it.obj() }
    if (sources.isEmpty()) return null
    val best = sources.minByOrNull { kotlin.math.abs(((it["width"] as? JsonPrimitive)?.intOrNull ?: 300) - 300) }
    return best?.str("url")
}

/** A "Track" object (from Liked Songs or a playlist); [wrapperUri] is the `_uri` of its wrapper. */
internal fun trackFromGraph(track: JsonElement?, wrapperUri: String? = null): SpotifyTrack? {
    val t = track.obj() ?: return null
    if (t.str("__typename")?.let { it != "Track" } == true) return null
    val uri = t.str("uri") ?: wrapperUri ?: return null
    if (!uri.startsWith("spotify:track:")) return null
    val name = t.str("name") ?: return null
    val duration = (t.path("trackDuration", "totalMilliseconds") ?: t.path("duration", "totalMilliseconds")) as? JsonPrimitive
    val artists = (t.path("artists", "items") as? JsonArray).orEmpty()
        .mapNotNull { it.obj()?.path("profile", "name")?.let { n -> (n as? JsonPrimitive)?.contentOrNull } }
    val album = t["albumOfTrack"].obj()
    return SpotifyTrack(
        id = uri.removePrefix("spotify:track:"),
        title = name,
        artist = artists.joinToString(", "),
        album = album?.str("name").orEmpty(),
        durationMs = duration?.longOrNull ?: 0L,
        imageUrl = coverFrom(album?.get("coverArt")),
    )
}

/** Songs of a `fetchPlaylist` answer (data.playlistV2.content.items[].itemV2). */
internal fun playlistPageTracks(root: JsonObject): List<SpotifyTrack> =
    (root.path("data", "playlistV2", "content", "items") as? JsonArray).orEmpty().mapNotNull { item ->
        val wrapper = item.obj()?.get("itemV2").obj() ?: return@mapNotNull null
        trackFromGraph(wrapper["data"], wrapper.str("_uri"))
    }

/** Number of entries in a `fetchPlaylist` page (to know when to stop paging). */
internal fun playlistPageSize(root: JsonObject): Int = (root.path("data", "playlistV2", "content", "items") as? JsonArray)?.size ?: 0

/** Songs of a `fetchLibraryTracks` answer (data.me.library.tracks.items[].track). */
internal fun likedPageTracks(root: JsonObject): List<SpotifyTrack> =
    (root.path("data", "me", "library", "tracks", "items") as? JsonArray).orEmpty().mapNotNull { item ->
        val wrapper = item.obj()?.get("track").obj() ?: return@mapNotNull null
        trackFromGraph(wrapper["data"], wrapper.str("_uri"))
    }

internal fun likedPageSize(root: JsonObject): Int = (root.path("data", "me", "library", "tracks", "items") as? JsonArray)?.size ?: 0

/**
 * Playlists anywhere in a `libraryV3` answer: every `{ "_uri": "spotify:playlist:…", "data": { Playlist } }`
 * (folders included). Searched for rather than read from a fixed path.
 */
internal fun libraryPlaylists(root: JsonElement): List<SpotifyPlaylist> {
    val out = LinkedHashMap<String, SpotifyPlaylist>()
    fun visit(e: JsonElement?) {
        when (e) {
            is JsonObject -> {
                val data = e["data"].obj()
                val uri = e.str("_uri") ?: data?.str("uri")
                if (uri != null && uri.startsWith("spotify:playlist:") && data?.str("__typename") == "Playlist") {
                    val id = uri.removePrefix("spotify:playlist:")
                    out.getOrPut(id) {
                        SpotifyPlaylist(
                            id = id,
                            name = data.str("name").orEmpty().ifBlank { "Playlist" },
                            owner = (data.path("ownerV2", "data", "name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            trackCount = ((data.path("content", "totalCount") ?: data.path("totalCount")) as? JsonPrimitive)?.intOrNull ?: 0,
                            imageUrl = ((data.path("images", "items") as? JsonArray)?.firstOrNull()).let(::coverFrom),
                        )
                    }
                }
                e.values.forEach(::visit)
            }
            is JsonArray -> e.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return out.values.toList()
}

/** Entries in a `libraryV3` page, to know when to stop paging. */
internal fun libraryPageSize(root: JsonObject): Int = (root.path("data", "me", "libraryV3", "items") as? JsonArray)?.size ?: 0

/** The account's display name from `profileAttributes` (null for a guest: no account behind the token). */
internal fun profileName(root: JsonObject): String? {
    val profile = root.path("data", "me", "profile").obj() ?: return null
    return profile.str("name")?.takeIf { it.isNotBlank() } ?: profile.str("username")
}

/** Spotify's error codes in a GraphQL answer, e.g. "PersistedQueryNotFound". */
internal fun graphErrors(root: JsonObject): List<String> =
    (root["errors"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.str("message") }

/** `"operationName","query","<sha256>"` pairs in the web player's JavaScript. */
internal fun operationHashes(js: String): Map<String, String> =
    Regex("\"([A-Za-z0-9_]+)\",\"(?:query|mutation)\",\"([0-9a-f]{64})\"").findAll(js)
        .associate { it.groupValues[1] to it.groupValues[2] }
