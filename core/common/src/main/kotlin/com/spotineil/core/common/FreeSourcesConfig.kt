package com.spotineil.core.common

/** Keys for the optional free music sources. A source is hidden when its key isn't configured. */
data class FreeSourcesConfig(
    val jamendoClientId: String,
    /** Identifies Spotineil to the Audius API (required `app_name` parameter). */
    val audiusAppName: String = "Spotineil",
    /** Last.fm API key: enables the "Last.fm top tracks" chart (played as official previews). */
    val lastFmApiKey: String = "",
    /** YouTube Data API v3 key: the main music catalog (played in YouTube's official embedded player). */
    val youTubeApiKey: String = "",
    /** Daily YouTube quota budget in units (Google's default is 10,000; keep a safety margin). */
    val youTubeDailyBudget: Int = 9_000,
) {
    val isJamendoEnabled: Boolean get() = jamendoClientId.isNotBlank()
    val isLastFmEnabled: Boolean get() = lastFmApiKey.isNotBlank()
    val isYouTubeEnabled: Boolean get() = youTubeApiKey.isNotBlank()
}
