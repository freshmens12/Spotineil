pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // The Spotify App Remote SDK is distributed only as an AAR on GitHub releases.
        // Resolve it straight from the official release so no binary is committed to this repo.
        ivy {
            name = "SpotifyAndroidSdkReleases"
            url = uri("https://github.com/spotify/android-sdk/releases/download/")
            patternLayout {
                artifact("v[revision]-appremote_v2.1.0-auth/[artifact]-release-[revision].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.spotify.android", "spotify-app-remote") }
        }
    }
}

rootProject.name = "Spotineil"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:data")
include(":core:player")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":feature:home")
include(":feature:search")
include(":feature:library")
include(":feature:player")
include(":feature:artist")
include(":feature:album")
include(":feature:playlist")
include(":feature:profile")
include(":feature:settings")
include(":feature:discover")
