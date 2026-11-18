package com.spotineil.core.network.auth

import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.network.api.SpotifyAccountsApi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises token refreshes so that N concurrent 401s trigger exactly one refresh call.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val accountsApi: SpotifyAccountsApi,
    private val tokenStore: AuthTokenStore,
    private val config: SpotifyAppConfig,
    private val clock: Clock,
) {
    private val lock = Any()

    sealed interface Result {
        data class Refreshed(val tokens: AuthTokens) : Result
        /** Refresh token rejected; the session is over. */
        data object SessionExpired : Result
        /** Transient failure (network/5xx); keep the session. */
        data object Failed : Result
    }

    /**
     * Returns a valid access token. If [staleAccessToken] is no longer the stored token,
     * another thread already refreshed and we just return the new one.
     */
    fun refresh(staleAccessToken: String?): Result = synchronized(lock) {
        val current = tokenStore.get() ?: return Result.SessionExpired
        if (staleAccessToken != null && current.accessToken != staleAccessToken &&
            !current.isExpired(clock.now())
        ) {
            return Result.Refreshed(current)
        }
        val response = try {
            accountsApi.refresh(refreshToken = current.refreshToken, clientId = config.clientId).execute()
        } catch (e: IOException) {
            return Result.Failed
        }
        val body = response.body()
        return when {
            response.isSuccessful && body != null -> {
                val refreshed = AuthTokens(
                    accessToken = body.accessToken,
                    // Spotify may or may not rotate the refresh token.
                    refreshToken = body.refreshToken ?: current.refreshToken,
                    expiresAtEpochMs = clock.now() + body.expiresIn * 1000,
                    scope = body.scope ?: current.scope,
                )
                tokenStore.save(refreshed)
                Result.Refreshed(refreshed)
            }
            response.code() == 400 || response.code() == 401 -> {
                tokenStore.clear()
                Result.SessionExpired
            }
            else -> Result.Failed
        }
    }
}

fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
