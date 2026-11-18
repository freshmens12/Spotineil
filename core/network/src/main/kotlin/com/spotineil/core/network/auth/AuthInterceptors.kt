package com.spotineil.core.network.auth

import javax.inject.Inject
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Attaches the bearer token, refreshing proactively if it has already expired. */
class AuthInterceptor @Inject constructor(
    private val tokenStore: AuthTokenStore,
    private val refresher: TokenRefresher,
    private val clock: Clock,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var tokens = tokenStore.get() ?: return chain.proceed(chain.request())
        if (tokens.isExpired(clock.now())) {
            (refresher.refresh(tokens.accessToken) as? TokenRefresher.Result.Refreshed)?.let { tokens = it.tokens }
        }
        return chain.proceed(chain.request().withBearer(tokens.accessToken))
    }
}

/** Handles 401 by refreshing the token once and retrying the original request. */
class TokenAuthenticator @Inject constructor(
    private val refresher: TokenRefresher,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponseCount() >= MAX_RETRIES) return null
        val sent = response.request.header(AUTHORIZATION)?.removePrefix(BEARER) ?: return null
        return when (val result = refresher.refresh(sent)) {
            is TokenRefresher.Result.Refreshed -> response.request.withBearer(result.tokens.accessToken)
            else -> null
        }
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }

    private companion object { const val MAX_RETRIES = 1 }
}

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

private fun Request.withBearer(token: String): Request =
    newBuilder().header(AUTHORIZATION, BEARER + token).build()
