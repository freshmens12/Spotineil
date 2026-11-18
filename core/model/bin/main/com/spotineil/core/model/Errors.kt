package com.spotineil.core.model

/** Domain-level failures. Repositories translate HTTP/IO errors into these. */
sealed class SpotineilException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** No connectivity or a timeout. Retry is meaningful. */
    class Network(cause: Throwable? = null) : SpotineilException("Network unavailable", cause)

    /** Session expired and refresh failed — the user must log in again. */
    class Unauthorized : SpotineilException("Session expired")

    /** 403. Includes Premium-only Web API calls and endpoints restricted for new apps. */
    class Forbidden(val reason: String? = null) : SpotineilException(reason ?: "Forbidden") {
        val isPremiumRequired: Boolean get() = reason == "PREMIUM_REQUIRED"
    }

    /** 404 — also used by Spotify for endpoints unavailable to this app. */
    class NotFound : SpotineilException("Not found")

    class RateLimited(val retryAfterSeconds: Long) : SpotineilException("Rate limited")

    class Server(val code: Int) : SpotineilException("Server error $code")

    /** YouTube daily quota used up (resets at midnight Pacific time). */
    class QuotaExceeded : SpotineilException("Daily YouTube limit reached")

    class Unknown(cause: Throwable? = null) : SpotineilException(cause?.message, cause)
}

/** True when a feature should be hidden rather than shown as an error. */
val Throwable.isUnavailableFeature: Boolean
    get() = this is SpotineilException.Forbidden || this is SpotineilException.NotFound
