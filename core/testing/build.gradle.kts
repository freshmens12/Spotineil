plugins {
    alias(libs.plugins.spotineil.android.library)
}

android {
    namespace = "com.spotineil.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))
    api(project(":core:player"))
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    api(libs.androidx.paging.common)
}
