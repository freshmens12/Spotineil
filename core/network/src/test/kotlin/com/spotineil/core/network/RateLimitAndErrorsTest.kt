package com.spotineil.core.network

import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.auth.AuthTokens
import com.spotineil.core.network.di.SpotineilJson
import com.spotineil.core.network.model.SearchResponseDto
import com.spotineil.core.network.model.toModel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RateLimitAndErrorsTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `short Retry-After is honored and the request retried`() {
        val waits = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor(RateLimitInterceptor(sleeper = { waits += it })).build()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(listOf(2_000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `long Retry-After is surfaced as RateLimited instead of blocking`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val stack = AuthStack(server, InMemoryTokenStore(AuthTokens("a", "r", Long.MAX_VALUE)))

        val error = runCatching { apiCall { stack.api.me() } }.exceptionOrNull()

        assertTrue(error is SpotineilException.RateLimited)
        assertEquals(120L, (error as SpotineilException.RateLimited).retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 PREMIUM_REQUIRED is mapped to a premium-required error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"status":403,"message":"Player command failed: Premium required","reason":"PREMIUM_REQUIRED"}}"""),
        )
        val stack = AuthStack(server, InMemoryTokenStore(AuthTokens("a", "r", Long.MAX_VALUE)))

        val error = runCatching { apiCall { stack.api.pause() } }.exceptionOrNull()

        assertTrue(error is SpotineilException.Forbidden)
        assertTrue((error as SpotineilException.Forbidden).isPremiumRequired)
    }

    @Test
    fun `404 for restricted endpoints is NotFound so features can hide`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val stack = AuthStack(server, InMemoryTokenStore(AuthTokens("a", "r", Long.MAX_VALUE)))
        val error = runCatching { apiCall { stack.api.newReleases() } }.exceptionOrNull()
        assertTrue(error is SpotineilException.NotFound)
    }

    @Test
    fun `no-content player state is handled as nothing playing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val stack = AuthStack(server, InMemoryTokenStore(AuthTokens("a", "r", Long.MAX_VALUE)))
        val response = apiCall { stack.api.playbackState() }
        assertEquals(null, response.body())
    }

    @Test
    fun `search response tolerates null items and unknown fields`() {
        val json = """
            {"tracks":{"items":[{"id":"t1","name":"Song","uri":"spotify:track:t1","duration_ms":1000,
              "artists":[{"id":"a","name":"Artist","uri":"spotify:artist:a"}],"new_field":true}],"total":1,"offset":0,"limit":10},
             "playlists":{"items":[null,{"id":"p1","name":"List","uri":"spotify:playlist:p1","owner":{"id":"o"},"images":null}],
               "total":2,"offset":0,"limit":10}}
        """.trimIndent()

        val dto = SpotineilJson.decodeFromString(SearchResponseDto.serializer(), json)

        val tracks = dto.tracks!!.items.filterNotNull().map { it.toModel() }
        assertEquals("Song", tracks.single().name)
        assertEquals("Artist", tracks.single().artistNames)
        val playlists = dto.playlists!!.items.filterNotNull().map { it.toModel() }
        assertEquals(listOf("List"), playlists.map { it.name })
        assertTrue(playlists.single().images.isEmpty())
    }
}
