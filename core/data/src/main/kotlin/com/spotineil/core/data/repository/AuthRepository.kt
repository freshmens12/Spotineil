package com.spotineil.core.data.repository

import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.data.maintenance.CacheManager
import com.spotineil.core.network.api.SpotifyAccountsApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.AuthTokenStore
import com.spotineil.core.network.auth.AuthTokens
import com.spotineil.core.network.auth.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>
    fun isLoggedInNow(): Boolean

    /** Completes Authorization Code + PKCE: exchanges [code] using the [codeVerifier]. */
    suspend fun completeLogin(code: String, codeVerifier: String)

    suspend fun logout()
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val accountsApi: SpotifyAccountsApi,
    private val tokenStore: AuthTokenStore,
    private val config: SpotifyAppConfig,
    private val clock: Clock,
    private val cacheManager: CacheManager,
) : AuthRepository {

    override val isLoggedIn: Flow<Boolean> = tokenStore.tokens.map { it != null }.distinctUntilChanged()

    override fun isLoggedInNow(): Boolean = tokenStore.get() != null

    override suspend fun completeLogin(code: String, codeVerifier: String) {
        val response = apiCall {
            accountsApi.exchangeCode(
                code = code,
                redirectUri = config.redirectUri,
                clientId = config.clientId,
                codeVerifier = codeVerifier,
            )
        }
        tokenStore.save(
            AuthTokens(
                accessToken = response.accessToken,
                refreshToken = requireNotNull(response.refreshToken) { "Spotify did not return a refresh token" },
                expiresAtEpochMs = clock.now() + response.expiresIn * 1000,
                scope = response.scope,
            ),
        )
    }

    override suspend fun logout() {
        tokenStore.clear()
        cacheManager.clearAll(includeUserData = true)
    }
}
