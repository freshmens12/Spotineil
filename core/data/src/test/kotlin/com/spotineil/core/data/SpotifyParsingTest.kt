package com.spotineil.core.data

import com.spotineil.core.data.spotifyweb.graphErrors
import com.spotineil.core.data.spotifyweb.libraryPlaylists
import com.spotineil.core.data.spotifyweb.likedPageTracks
import com.spotineil.core.data.spotifyweb.operationHashes
import com.spotineil.core.data.spotifyweb.playlistPageTracks
import com.spotineil.core.data.spotifyweb.profileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples shaped like the answers of open.spotify.com's GraphQL API (api-partner pathfinder). */
class SpotifyParsingTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val track = """{"__typename":"Track","name":"Wherever You Will Go","uri":"spotify:track:t1",
        "trackDuration":{"totalMilliseconds":208810},
        "artists":{"items":[{"profile":{"name":"The Calling"},"uri":"spotify:artist:a1"}]},
        "albumOfTrack":{"name":"Camino Palmero","coverArt":{"sources":[{"url":"big","width":640},{"url":"mid","width":300},{"url":"small","width":64}]}}}"""

    @Test fun `playlist page songs`() {
        val root = parse("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"__typename":"PlaylistItemsPage","items":[
            {"itemV2":{"__typename":"TrackResponseWrapper","data":$track}},
            {"itemV2":{"__typename":"EpisodeResponseWrapper","data":{"__typename":"Episode","name":"Podcast","uri":"spotify:episode:e1"}}}
        ]}}}}""")
        val songs = playlistPageTracks(root)
        assertEquals(1, songs.size)
        assertEquals("t1", songs[0].id)
        assertEquals("Wherever You Will Go", songs[0].title)
        assertEquals("The Calling", songs[0].artist)
        assertEquals(208810L, songs[0].durationMs)
        assertEquals("mid", songs[0].imageUrl)
    }

    @Test fun `liked songs take the uri from the wrapper`() {
        val noUri = track.replace("\"uri\":\"spotify:track:t1\",", "")
        val root = parse("""{"data":{"me":{"library":{"tracks":{"items":[{"addedAt":{},"track":{"_uri":"spotify:track:t9","data":$noUri}}],"totalCount":1}}}}}""")
        assertEquals(listOf("t9"), likedPageTracks(root).map { it.id })
    }

    @Test fun `library playlists are found wherever they are, folders included`() {
        val root = parse("""{"data":{"me":{"libraryV3":{"items":[
            {"item":{"_uri":"spotify:playlist:p1","data":{"__typename":"Playlist","name":"Road trip","ownerV2":{"data":{"name":"Vince"}},"images":{"items":[{"sources":[{"url":"cover","width":300}]}]}}}},
            {"item":{"_uri":"spotify:folder:f1","data":{"__typename":"Folder","name":"Old","items":[
                {"item":{"_uri":"spotify:playlist:p2","data":{"__typename":"Playlist","name":"Inside folder"}}}]}}},
            {"item":{"_uri":"spotify:collection:tracks","data":{"__typename":"PseudoPlaylist","name":"Liked Songs"}}}
        ],"totalCount":3}}}}""")
        val lists = libraryPlaylists(root)
        assertEquals(listOf("p1", "p2"), lists.map { it.id })
        assertEquals("Vince", lists[0].owner)
        assertEquals("cover", lists[0].imageUrl)
    }

    @Test fun `profile name, and none for a guest`() {
        assertEquals("Vince", profileName(parse("""{"data":{"me":{"profile":{"name":"Vince","username":"v123"}}}}""")))
        assertNull(profileName(parse("""{"data":{"me":null},"errors":[{"message":"Unauthorized"}]}""")))
    }

    @Test fun `query hashes are read from the web player script`() {
        val js = """x=new aX.l("profileAttributes","query","${"a".repeat(64)}",null);y=new aX.l("addToLibrary","mutation","${"b".repeat(64)}",null)"""
        val map = operationHashes(js)
        assertEquals("a".repeat(64), map["profileAttributes"])
        assertEquals("b".repeat(64), map["addToLibrary"])
    }

    @Test fun `graphql errors are listed`() {
        assertTrue(graphErrors(parse("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")).contains("PersistedQueryNotFound"))
    }
}
