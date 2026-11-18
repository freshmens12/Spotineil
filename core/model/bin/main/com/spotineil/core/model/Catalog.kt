package com.spotineil.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Image(val url: String, val width: Int? = null, val height: Int? = null)

/**
 * Picks the most appropriate image for a target pixel size. Spotify returns images
 * largest-first; with [dataSaver] we always prefer the smallest acceptable image.
 */
fun List<Image>.bestFor(targetPx: Int, dataSaver: Boolean = false): String? {
    if (isEmpty()) return null
    val sized = filter { it.width != null }.sortedBy { it.width }
    if (sized.isEmpty()) return first().url
    val effectiveTarget = if (dataSaver) targetPx / 2 else targetPx
    return (sized.firstOrNull { it.width!! >= effectiveTarget } ?: sized.last()).url
}

@Serializable
data class SimpleArtist(val id: String, val uri: String, val name: String)

@Serializable
data class SimpleAlbum(
    val id: String,
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
)

@Serializable
data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SimpleArtist>,
    val album: SimpleAlbum?,
    val durationMs: Long,
    val explicit: Boolean = false,
    val isPlayable: Boolean = true,
    val trackNumber: Int = 0,
    val isLocal: Boolean = false,
) {
    val artistNames: String get() = artists.joinToString(", ") { it.name }
    val images: List<Image> get() = album?.images.orEmpty()
}

enum class AlbumType { Album, Single, Compilation, Unknown }

@Serializable
data class Album(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SimpleArtist>,
    val images: List<Image>,
    val releaseDate: String? = null,
    val totalTracks: Int = 0,
    val albumType: AlbumType = AlbumType.Album,
    val label: String? = null,
    val tracks: List<Track> = emptyList(),
) {
    val releaseYear: String? get() = releaseDate?.take(4)
}

@Serializable
data class Artist(
    val id: String,
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
    val genres: List<String> = emptyList(),
    val followers: Int = 0,
)

@Serializable
data class UserRef(val id: String, val displayName: String?)

@Serializable
data class Playlist(
    val id: String,
    val uri: String,
    val name: String,
    val description: String?,
    val images: List<Image>,
    val owner: UserRef,
    val isPublic: Boolean?,
    val collaborative: Boolean,
    val trackCount: Int,
    val snapshotId: String? = null,
    val followers: Int = 0,
)

@Serializable
data class PlaylistTrack(
    /** Position of the item in the playlist (needed for reorder/remove). */
    val position: Int,
    val addedAt: String?,
    val track: Track,
)

@Serializable
data class Show(
    val id: String,
    val uri: String,
    val name: String,
    val publisher: String,
    val images: List<Image>,
    val description: String? = null,
)

@Serializable
data class Category(val id: String, val name: String, val icons: List<Image>)

enum class Product { Premium, Free, Open, Unknown;
    val isPremium: Boolean get() = this == Premium
}

@Serializable
data class User(
    val id: String,
    val displayName: String?,
    val email: String? = null,
    val country: String? = null,
    val product: Product = Product.Unknown,
    val images: List<Image> = emptyList(),
    val followers: Int = 0,
)

@Serializable
data class PlayHistoryItem(val track: Track, val playedAt: String)

/** Wraps a page from an offset-paginated endpoint. */
data class Page<T>(
    val items: List<T>,
    val offset: Int,
    val total: Int,
    val nextOffset: Int?,
)
