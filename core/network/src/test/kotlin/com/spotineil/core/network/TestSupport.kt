package com.spotineil.core.network

import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.network.api.SpotifyAccountsApi
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.auth.AuthInterceptor
import com.spotineil.core.network.auth.AuthTokenStore
import com.spotineil.core.network.auth.AuthTokens
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.auth.TokenAuthenticator
import com.spotineil.core.network.auth.TokenRefresher
import com.spotineil.core.network.di.SpotineilJson
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class InMemoryTokenStore(initial: AuthTokens? = null) : AuthTokenStore {
    override val tokens = MutableStateFlow(initial)
    override fun get(): AuthTokens? = tokens.value
    override fun save(tokens: AuthTokens) { this.tokens.value = tokens }
    override fun clear() { tokens.value = null }
}

class FixedClock(var nowMs: Long = 1_000_000L) : Clock {
    override fun now(): Long = nowMs
}

val testConfig = SpotifyAppConfig(
    clientId = "test-client",
    redirectUri = "spotineil://callback",
    scopes = SpotifyAppConfig.SCOPES,
    appVersion = "test",
)

/** Wires the real auth stack (interceptor + authenticator + refresher) against one MockWebServer. */
class AuthStack(server: MockWebServer, val store: InMemoryTokenStore, val clock: FixedClock = FixedClock()) {
    private val contentType = "application/json".toMediaType()

    val accounts: SpotifyAccountsApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(SpotineilJson.asConverterFactory(contentType))
        .build()
        .create(SpotifyAccountsApi::class.java)

    val refresher = TokenRefresher(accounts, store, testConfig, clock)

    val api: SpotifyApi = Retrofit.Builder()
        .baseUrl(server.url("/v1/"))
        .client(
            OkHttpClient.Builder()
                .addInterceptor(RateLimitInterceptor(sleeper = {}))
                .addInterceptor(AuthInterceptor(store, refresher, clock))
                .authenticator(TokenAuthenticator(refresher))
                .build(),
        )
        .addConverterFactory(SpotineilJson.asConverterFactory(contentType))
        .build()
        .create(SpotifyApi::class.java)
}

const val ME_JSON = """{"id":"me","display_name":"Tester","product":"premium","followers":{"total":3}}"""
