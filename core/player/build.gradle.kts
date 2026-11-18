plugins {
    alias(libs.plugins.spotineil.android.library)
    alias(libs.plugins.spotineil.hilt)
}

android {
    namespace = "com.spotineil.core.player"
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    // Official Spotify App Remote SDK (resolved from GitHub releases, see settings.gradle.kts).
    implementation(libs.spotify.app.remote) { artifact { type = "aar" } }
    implementation(libs.gson) // required at runtime by App Remote's JSON mapper

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
}
