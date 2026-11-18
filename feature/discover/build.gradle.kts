plugins {
    alias(libs.plugins.spotineil.android.feature)
}

android {
    namespace = "com.spotineil.feature.discover"
}

dependencies {
    // YouTube's official IFrame player in a WebView (Apache-2.0). Playback stays inside YouTube's player.
    implementation(libs.androidyoutubeplayer.core)
}
