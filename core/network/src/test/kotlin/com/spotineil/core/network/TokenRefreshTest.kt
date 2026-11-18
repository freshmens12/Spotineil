package com.spotineil.core.network

import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.auth.AuthTokens
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TokenRefreshTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun tokens(access: String = "old-access", expiresAt: Long = 10_000_000L) =
        AuthTokens(accessToken = access, refreshToken = "refresh-1", expiresAtEpochMs = expiresAt)

    /** /v1/me accepts only "new-access"; /api/token returns [tokenResponse]. */
    private fun dispatch(tokenResponse: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/token" -> tokenResponse
                "/v1/me" -> if (request.getHeader("Authorization") == "Bearer new-access") {
                    MockResponse().setBody(ME_JSON)
                } else {
                    MockResponse().setResponseCode(401)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `401 triggers a single refresh and the request is retried with the new token`() = runTest {
        dispatch(MockResponse().setBody("""{"access_token":"new-access","expires_in":3600}"""))
        val stack = AuthStack(server, InMemoryTokenStore(tokens()))

        val me = apiCall { stack.api.me() }

        assertEquals("me", me.id)
        val saved = stack.store.get()!!
        assertEquals("new-access", saved.accessToken)
        // Spotify didn't rotate the refresh token, so the old one is kept.
        assertEquals("refresh-1", saved.refreshToken)
        assertEquals(stack.clock.now() + 3_600_000, saved.expiresAtEpochMs)

        val first = server.takeRequest()
        assertEquals("Bearer old-access", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/api/token", refresh.path)
        val form = refresh.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertTrue(form.contains("client_id=test-client"))
        assertTrue("PKCE flow must never send a client secret", !form.contains("client_secret"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `rotated refresh token is persisted`() = runTest {
        dispatch(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"refresh-2","expires_in":3600}"""))
        val stack = AuthStack(server, InMemoryTokenStore(tokens()))
        apiCall { stack.api.me() }
        assertEquals("refresh-2", stack.store.get()!!.refreshToken)
    }

    @Test
    fun `expired token is refreshed proactively before the request`() = runTest {
        dispatch(MockResponse().setBody("""{"access_token":"new-access","expires_in":3600}"""))
        val clock = FixedClock(nowMs = 5_000_000L)
        val stack = AuthStack(server, InMemoryTokenStore(tokens(expiresAt = 4_000_000L)), clock)

        apiCall { stack.api.me() }

        assertEquals("/api/token", server.takeRequest().path)
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `rejected refresh token ends the session`() = runTest {
        dispatch(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val stack = AuthStack(server, InMemoryTokenStore(tokens()))

        try {
            apiCall { stack.api.me() }
            fail("Expected Unauthorized")
        } catch (e: SpotineilException.Unauthorized) {
            // expected
        }
        assertNull("Tokens must be cleared so the app returns to login", stack.store.get())
    }

    @Test
    fun `transient refresh failure keeps the session`() = runTest {
        dispatch(MockResponse().setResponseCode(503))
        val stack = AuthStack(server, InMemoryTokenStore(tokens()))

        runCatching { apiCall { stack.api.me() } }

        assertEquals("old-access", stack.store.get()?.accessToken)
    }
}
