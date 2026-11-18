plugins {
    alias(libs.plugins.spotineil.android.feature)
}

android {
    namespace = "com.spotineil.feature.player"
}

dependencies {
    implementation(libs.reorderable)
}
