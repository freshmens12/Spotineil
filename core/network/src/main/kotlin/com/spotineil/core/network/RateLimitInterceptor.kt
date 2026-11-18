package com.spotineil.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Honors HTTP 429 `Retry-After`. Short waits are retried transparently (bounded); long
 * waits are surfaced to the caller as a [com.spotineil.core.model.SpotineilException.RateLimited].
 */
class RateLimitInterceptor(
    private val maxRetries: Int = 2,
    private val maxWaitSeconds: Long = 10,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0
        while (response.code == 429 && attempt < maxRetries) {
            val wait = response.retryAfterSeconds()
            if (wait > maxWaitSeconds) break
            response.close()
            sleeper(wait * 1000)
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }
}

fun Response.retryAfterSeconds(default: Long = 1): Long =
    header("Retry-After")?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: default
