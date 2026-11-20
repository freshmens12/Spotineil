package com.spotineil.core.common

/** Keys for the optional free music sources. A source is hidden when its key isn't configured. */
data class FreeSourcesConfig(
    val jamendoClientId: String,
    /** Identifies Spotineil to the Audius API (required `app_name` parameter). */
    val audiusAppName: String = "Spotineil",
    /** Last.fm API key: enables the "Last.fm top tracks" chart (metadata only). */
    val lastFmApiKey: String = "",
) {
    val isJamendoEnabled: Boolean get() = jamendoClientId.isNotBlank()
    val isLastFmEnabled: Boolean get() = lastFmApiKey.isNotBlank()
}
