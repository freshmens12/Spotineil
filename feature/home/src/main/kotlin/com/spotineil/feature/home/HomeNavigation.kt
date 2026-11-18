package com.spotineil.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

fun NavGraphBuilder.homeScreen(
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onLikedSongsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    composable<HomeRoute> {
        HomeRoute(onArtistClick, onAlbumClick, onPlaylistClick, onLikedSongsClick, onProfileClick, onSettingsClick)
    }
}
