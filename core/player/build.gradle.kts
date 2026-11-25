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
    implementation(project(":core:separation"))
    // Runs the karaoke voice model on the phone (the model itself is downloaded on first use).
    implementation(libs.onnxruntime.android)
    // MP3 encoder for downloads (pure-Java LAME port, LGPL 2.1+)
    implementation(libs.jump3r)

    // Official Spotify App Remote SDK (resolved from GitHub releases, see settings.gradle.kts).
    implementation(libs.spotify.app.remote) { artifact { type = "aar" } }
    implementation(libs.gson) // required at runtime by App Remote's JSON mapper

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.core)
}
