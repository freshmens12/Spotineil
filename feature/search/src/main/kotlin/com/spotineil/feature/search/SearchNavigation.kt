package com.spotineil.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object SearchRoute

fun NavGraphBuilder.searchScreen(callbacks: SearchCallbacks) {
    composable<SearchRoute> { SearchRoute(callbacks) }
}
