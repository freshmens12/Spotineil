package com.spotineil.feature.discover.free

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.CollectionKind
import com.spotineil.core.model.Podcast
import kotlinx.serialization.Serializable

// Bottom-bar tabs of the no-account app.
@Serializable data object FreeHomeTab
@Serializable data object FreeSearchTab
@Serializable data object FreeLibraryTab

@Serializable data object LikedMediaRoute
@Serializable data object HistoryRoute

/** YouTube's official player; the queue lives in [FreePlayer]. */
@Serializable data object YouTubePlayerRoute

@Serializable
data class PodcastRoute(val feedUrl: String, val title: String, val author: String, val artworkUrl: String? = null) {
    fun toPodcast() = Podcast(feedUrl, title, author, artworkUrl)
}

@Serializable
data class CollectionRoute(val id: String, val title: String, val creator: String, val date: String? = null, val kind: String = CollectionKind.Album.name) {
    fun toCollection() = ArchiveCollection(id, title, creator, date, runCatching { CollectionKind.valueOf(kind) }.getOrDefault(CollectionKind.Album))
}

/** [kind] is a [BrowseKind] name; [genre] is an Audius genre, [tag] a Radio Browser tag. */
@Serializable
data class BrowseRoute(val kind: String, val title: String, val genre: String? = null, val tag: String? = null)

fun Podcast.toRoute() = PodcastRoute(feedUrl, title, author, artworkUrl)
fun ArchiveCollection.toRoute() = CollectionRoute(id, title, creator, date, kind.name)

/** All screens of the free app. Tabs + detail pages; [nav] comes from the app's NavController. */
fun NavGraphBuilder.freeScreens(nav: FreeNavigation) {
    composable<FreeHomeTab> { FreeHomeRoute(nav) }
    composable<FreeSearchTab> { FreeSearchRoute(nav) }
    composable<FreeLibraryTab> { FreeLibraryRoute(nav) }
    composable<LikedMediaRoute> { LikedMediaScreen(nav) }
    composable<HistoryRoute> { HistoryScreen(nav) }
    composable<PodcastRoute> { PodcastScreen(nav) }
    composable<CollectionRoute> { CollectionScreen(nav) }
    composable<BrowseRoute> { BrowseScreen(nav) }
    composable<YouTubePlayerRoute> { YouTubePlayerScreen(nav) }
}
