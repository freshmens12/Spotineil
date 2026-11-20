import java.util.Properties

plugins {
    alias(libs.plugins.spotineil.android.application)
    alias(libs.plugins.spotineil.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets come from local.properties (dev) or environment variables (CI). Never commit them.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? =
    (localProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.spotineil.app"

    defaultConfig {
        applicationId = "com.spotineil.app"
        // Releases bump these; -PappVersionName / -PappVersionCode override them (used to test updates).
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 4
        versionName = (findProperty("appVersionName") as String?) ?: "1.3.0"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${secret("SPOTIFY_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"spotineil://callback\"")
        // Where the app looks for new versions (public releases of Spotineil-releases).
        buildConfigField("String", "UPDATE_URL", "\"https://api.github.com/repos/freshmens12/Spotineil-releases/releases/latest\"")
        // Optional free key from https://devportal.jamendo.com — Jamendo is hidden without it.
        buildConfigField("String", "JAMENDO_CLIENT_ID", "\"${secret("JAMENDO_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "LASTFM_API_KEY", "\"${secret("LASTFM_API_KEY") ?: ""}\"")
        // AppAuth's RedirectUriReceiverActivity listens on this scheme.
        manifestPlaceholders["appAuthRedirectScheme"] = "spotineil"
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = secret("SPOTINEIL_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = secret("SPOTINEIL_KEYSTORE_PASSWORD")
                keyAlias = secret("SPOTINEIL_KEY_ALIAS")
                keyPassword = secret("SPOTINEIL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (secret("SPOTINEIL_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:artist"))
    implementation(project(":feature:album"))
    implementation(project(":feature:playlist"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:discover"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.appauth)

    testImplementation(libs.junit4)
}
