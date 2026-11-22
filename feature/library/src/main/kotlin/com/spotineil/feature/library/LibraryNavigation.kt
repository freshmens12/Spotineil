package com.spotineil.feature.library

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object LibraryRoute
@Serializable data object LikedSongsRoute
@Serializable data object LocalFilesRoute
@Serializable data object DownloadsRoute

fun NavController.navigateToLikedSongs() = navigate(LikedSongsRoute)
fun NavController.navigateToLocalFiles() = navigate(LocalFilesRoute)
fun NavController.navigateToDownloads() = navigate(DownloadsRoute)

fun NavGraphBuilder.libraryScreens(
    callbacks: LibraryCallbacks,
    onBack: () -> Unit,
) {
    composable<LibraryRoute> { LibraryRoute(callbacks) }
    composable<LikedSongsRoute> {
        LikedSongsRoute(onBack, callbacks.onArtistClick, callbacks.onAlbumClick, callbacks.onMessage)
    }
    composable<LocalFilesRoute> { LocalFilesRoute(onBack) }
    composable<DownloadsRoute> { LocalFilesRoute(onBack, downloadsOnly = true) }
}

/** Guest mode: local music as a top-level tab (no back button). */
@Serializable data object LocalMusicTabRoute

fun NavGraphBuilder.localMusicTab() {
    composable<LocalMusicTabRoute> { LocalFilesRoute(onBack = null) }
}
