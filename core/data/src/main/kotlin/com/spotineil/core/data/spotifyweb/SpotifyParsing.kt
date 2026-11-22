package com.spotineil.core.data.spotifyweb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** One entry of `me/playlists` (null for entries without an id). */
internal fun playlistFrom(element: JsonElement): SpotifyPlaylist? {
    val p = element as? JsonObject ?: return null
    val id = p.string("id") ?: return null
    // The song count is "tracks.total" (older) or "items.total" (newer).
    val total = (p["tracks"] as? JsonObject)?.int("total") ?: (p["items"] as? JsonObject)?.int("total") ?: 0
    return SpotifyPlaylist(
        id = id,
        name = p.string("name").orEmpty().ifBlank { "Playlist" },
        owner = (p["owner"] as? JsonObject)?.string("display_name").orEmpty(),
        trackCount = total,
        imageUrl = ((p["images"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("url"),
    )
}

/** A song object; null for podcast episodes, local files (no id) and removed songs. */
internal fun trackFrom(element: JsonElement?): SpotifyTrack? {
    val t = element as? JsonObject ?: return null
    if (t.string("type") == "episode") return null
    val id = t.string("id") ?: return null
    val title = t.string("name") ?: return null
    val artists = (t["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }
    val album = t["album"] as? JsonObject
    return SpotifyTrack(
        id = id,
        title = title,
        artist = artists.joinToString(", "),
        album = album?.string("name").orEmpty(),
        durationMs = (t["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
        // The middle-sized cover when there is one.
        imageUrl = ((album?.get("images") as? JsonArray)?.let { imgs -> imgs.getOrNull(1) ?: imgs.firstOrNull() } as? JsonObject)?.string("url"),
    )
}

/** An entry of `me/tracks` ("track") or of a playlist's songs ("track", or "item" in newer answers). */
internal fun trackFromEntry(entry: JsonElement): SpotifyTrack? {
    val obj = entry as? JsonObject ?: return null
    return trackFrom(obj["track"] ?: obj["item"])
}
