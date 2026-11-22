plugins {
    alias(libs.plugins.spotineil.android.feature)
}

android {
    namespace = "com.spotineil.feature.discover"
}

dependencies {
    implementation(libs.reorderable)
}
