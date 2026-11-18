package com.spotineil.core.common

/**
 * Spotify developer-app configuration. Provided by :app from BuildConfig (sourced from
 * local.properties / CI secrets), so library modules never see the build config directly.
 */
data class SpotifyAppConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val appVersion: String,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    companion object {
        val SCOPES = listOf(
            "user-read-private",
            "user-read-email",
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-recently-played",
            "user-top-read",
            "user-follow-read",
            "user-follow-modify",
            "app-remote-control",
            "streaming",
        )
    }
}
