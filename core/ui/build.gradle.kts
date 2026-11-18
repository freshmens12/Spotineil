plugins {
    alias(libs.plugins.spotineil.android.library.compose)
    alias(libs.plugins.spotineil.hilt)
}

android {
    namespace = "com.spotineil.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
