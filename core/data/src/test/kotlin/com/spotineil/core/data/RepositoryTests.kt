package com.spotineil.core.data

import app.cash.turbine.test
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.data.repository.DefaultHomeRepository
import com.spotineil.core.data.repository.DefaultLyricsRepository
import com.spotineil.core.data.repository.DefaultSearchRepository
import com.spotineil.core.data.repository.LyricsQuery
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.api.LrclibApi
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.di.SpotineilJson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RepositoryTests {

    private lateinit var server: MockWebServer
    private val contentType = "application/json".toMediaType()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private inline fun <reified T> retrofit(path: String): T = Retrofit.Builder()
        .baseUrl(server.url(path))
        .client(OkHttpClient())
        .addConverterFactory(SpotineilJson.asConverterFactory(contentType))
        .build()
        .create(T::class.java)

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    // ---------- Lyrics (LRCLIB) ----------

    private val query = LyricsQuery("Test Track", "The Test Band", "Debug Sessions", durationMs = 200_400)

    @Test
    fun `lyrics exact match sends track, artist, album and rounded duration`() = runTest {
        route { MockResponse().setBody("""{"id":1,"duration":200,"syncedLyrics":"[00:01.00]alpha\n[00:03.00]beta"}""") }
        val repo = DefaultLyricsRepository(retrofit<LrclibApi>("/api/"), FakeLyricsDao(), TickingClock())

        val lyrics = repo.lyrics(query)!!

        assertTrue(lyrics.isSynced)
        assertEquals(listOf("alpha", "beta"), lyrics.lines.map { it.text })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Test Track", url.queryParameter("track_name"))
        assertEquals("The Test Band", url.queryParameter("artist_name"))
        assertEquals("Debug Sessions", url.queryParameter("album_name"))
        assertEquals("200", url.queryParameter("duration"))
    }

    @Test
    fun `lyrics falls back to search and picks synced match within duration tolerance`() = runTest {
        route { req ->
            when (req.requestUrl!!.encodedPath) {
                "/api/get" -> MockResponse().setResponseCode(404)
                "/api/search" -> MockResponse().setBody(
                    """[
                      {"id":1,"duration":260,"syncedLyrics":"[00:01.00]wrong length"},
                      {"id":2,"duration":201,"plainLyrics":"plain only"},
                      {"id":3,"duration":199,"syncedLyrics":"[00:02.00]right one"}
                    ]""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repo = DefaultLyricsRepository(retrofit<LrclibApi>("/api/"), FakeLyricsDao(), TickingClock())

        val lyrics = repo.lyrics(query)!!

        assertEquals("right one", lyrics.lines.single().text)
    }

    @Test
    fun `lyrics not found is cached so LRCLIB is not hammered`() = runTest {
        route { req -> if (req.requestUrl!!.encodedPath == "/api/search") MockResponse().setBody("[]") else MockResponse().setResponseCode(404) }
        val dao = FakeLyricsDao()
        val repo = DefaultLyricsRepository(retrofit<LrclibApi>("/api/"), dao, TickingClock())

        assertNull(repo.lyrics(query))
        val calls = server.requestCount
        assertNull(repo.lyrics(query))

        assertEquals(calls, server.requestCount)
        assertTrue(dao.rows.values.single().notFound)
    }

    // ---------- Search: recent searches ----------

    @Test
    fun `recent searches are de-duplicated, newest first, and trimmed`() = runTest {
        val repo = DefaultSearchRepository(retrofit<SpotifyApi>("/v1/"), FakeRecentSearchDao(), TickingClock())

        repo.addRecentSearch("  alpha ")
        repo.addRecentSearch("beta")
        repo.addRecentSearch("alpha")
        repo.addRecentSearch("")

        repo.recentSearches.test {
            assertEquals(listOf("alpha", "beta"), awaitItem().map { it.query })
        }
        repo.removeRecentSearch("alpha")
        repo.recentSearches.test { assertEquals(listOf("beta"), awaitItem().map { it.query }) }
    }

    @Test
    fun `search maps all result types and skips null entries`() = runTest {
        route {
            MockResponse().setBody(
                """{"tracks":{"items":[{"id":"t1","name":"Song","uri":"spotify:track:t1"}],"total":1},
                    "artists":{"items":[{"id":"a1","name":"Band","uri":"spotify:artist:a1"}],"total":1},
                    "playlists":{"items":[null],"total":1}}""",
            )
        }
        val repo = DefaultSearchRepository(retrofit<SpotifyApi>("/v1/"), FakeRecentSearchDao(), TickingClock())

        val results = repo.search("song", com.spotineil.core.model.SearchType.entries.toSet())

        assertEquals("Song", results.tracks.single().name)
        assertEquals("Band", results.artists.single().name)
        assertTrue(results.playlists.isEmpty())
        assertEquals("track,artist,album,playlist,show", server.takeRequest().requestUrl!!.queryParameter("type"))
    }

    // ---------- Home: graceful degradation ----------

    @Test
    fun `home renders available sections when restricted endpoints return 403 or 404`() = runTest {
        route { req ->
            when (req.requestUrl!!.encodedPath) {
                "/v1/me/playlists" -> MockResponse().setBody("""{"items":[{"id":"p1","name":"Mine","uri":"spotify:playlist:p1","owner":{"id":"me"}}],"total":1}""")
                "/v1/browse/new-releases" -> MockResponse().setResponseCode(403)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val cache = JsonCache(FakeCachedResponseDao(), SpotineilJson, TickingClock())
        val repo = DefaultHomeRepository(retrofit<SpotifyApi>("/v1/"), cache)

        repo.refresh()

        repo.feed.test {
            val feed = awaitItem()
            assertNotNull(feed)
            assertEquals(listOf("Mine"), feed!!.playlists.map { it.name })
            assertTrue(feed.newReleases.isEmpty())
        }
    }

    @Test(expected = SpotineilException.Server::class)
    fun `home refresh fails only when every section fails`() = runTest {
        route { MockResponse().setResponseCode(500) }
        val cache = JsonCache(FakeCachedResponseDao(), SpotineilJson, TickingClock())
        DefaultHomeRepository(retrofit<SpotifyApi>("/v1/"), cache).refresh()
    }
}
