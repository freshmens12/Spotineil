package com.spotineil.core.network.auth

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val scope: String? = null,
) {
    fun isExpired(nowMs: Long, skewMs: Long = 60_000): Boolean = nowMs >= expiresAtEpochMs - skewMs
}

/**
 * Secure token persistence. Implemented in :core:data (DataStore + Tink AEAD backed by the
 * Android Keystore). Reads are synchronous because OkHttp interceptors run on worker threads.
 */
interface AuthTokenStore {
    val tokens: StateFlow<AuthTokens?>
    fun get(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}
