plugins {
    alias(libs.plugins.spotineil.android.library)
    alias(libs.plugins.spotineil.hilt)
}

android {
    namespace = "com.spotineil.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
