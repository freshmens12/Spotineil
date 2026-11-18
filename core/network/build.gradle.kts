plugins {
    alias(libs.plugins.spotineil.android.library)
    alias(libs.plugins.spotineil.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.spotineil.core.network"
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "SPOTIFY_API_BASE_URL", "\"https://api.spotify.com/v1/\"")
        buildConfigField("String", "SPOTIFY_ACCOUNTS_BASE_URL", "\"https://accounts.spotify.com/\"")
        buildConfigField("String", "LRCLIB_BASE_URL", "\"https://lrclib.net/api/\"")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.okhttp.core)
    api(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
