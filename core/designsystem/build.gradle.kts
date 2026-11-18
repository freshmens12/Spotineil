plugins {
    alias(libs.plugins.spotineil.android.library.compose)
}

android {
    namespace = "com.spotineil.core.designsystem"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui)
    api(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.core.ktx)
}
