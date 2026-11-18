package com.spotineil.core.network.model

import com.spotineil.core.model.Album
import com.spotineil.core.model.AlbumType
import com.spotineil.core.model.Artist
import com.spotineil.core.model.Category
import com.spotineil.core.model.Device
import com.spotineil.core.model.Image
import com.spotineil.core.model.Page
import com.spotineil.core.model.PlayHistoryItem
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.PlaylistTrack
import com.spotineil.core.model.Product
import com.spotineil.core.model.Show
import com.spotineil.core.model.SimpleAlbum
import com.spotineil.core.model.SimpleArtist
import com.spotineil.core.model.Track
import com.spotineil.core.model.User
import com.spotineil.core.model.UserRef

fun ImageDto.toModel() = Image(url = url, width = width, height = height)
fun List<ImageDto>?.toModels(): List<Image> = orEmpty().map { it.toModel() }

fun SimpleArtistDto.toModel() = SimpleArtist(id = id.orEmpty(), uri = uri.orEmpty(), name = name)

fun SimpleAlbumDto.toModel() = SimpleAlbum(id = id.orEmpty(), uri = uri.orEmpty(), name = name, images = images.toModels())

/**
 * Converts a track (or episode) item. [fallbackAlbum] is used for album-track listings,
 * which omit the album object.
 */
fun TrackDto.toModel(fallbackAlbum: SimpleAlbum? = null): Track {
    val isEpisode = type == "episode"
    val artists = if (isEpisode && show != null) {
        listOf(SimpleArtist(show.id.orEmpty(), show.uri.orEmpty(), show.publisher ?: show.name))
    } else artists.map { it.toModel() }
    val album = when {
        album != null -> album.toModel()
        isEpisode && show != null -> SimpleAlbum(show.id.orEmpty(), show.uri.orEmpty(), show.name, images.toModels())
        else -> fallbackAlbum
    }
    return Track(
        id = id ?: uri.orEmpty(),
        uri = uri.orEmpty(),
        name = name,
        artists = artists,
        album = album,
        durationMs = durationMs,
        explicit = explicit,
        isPlayable = isPlayable ?: true,
        trackNumber = trackNumber,
        isLocal = isLocal,
    )
}

private fun String?.toAlbumType() = when (this?.lowercase()) {
    "album" -> AlbumType.Album
    "single" -> AlbumType.Single
    "compilation" -> AlbumType.Compilation
    else -> AlbumType.Unknown
}

fun AlbumDto.toModel(): Album {
    val simple = SimpleAlbum(id, uri, name, images.toModels())
    return Album(
        id = id,
        uri = uri,
        name = name,
        artists = artists.map { it.toModel() },
        images = images.toModels(),
        releaseDate = releaseDate,
        totalTracks = totalTracks,
        albumType = albumType.toAlbumType(),
        label = label,
        tracks = tracks?.items.orEmpty().map { it.toModel(fallbackAlbum = simple) },
    )
}

fun SimpleAlbumDto.toAlbum(): Album = Album(
    id = id.orEmpty(),
    uri = uri.orEmpty(),
    name = name,
    artists = artists.map { it.toModel() },
    images = images.toModels(),
    releaseDate = releaseDate,
    totalTracks = totalTracks,
    albumType = albumType.toAlbumType(),
)

fun ArtistDto.toModel() = Artist(
    id = id,
    uri = uri,
    name = name,
    images = images.toModels(),
    genres = genres,
    followers = followers?.total ?: 0,
)

fun PlaylistDto.toModel() = Playlist(
    id = id,
    uri = uri,
    name = name,
    description = description?.takeIf { it.isNotBlank() },
    images = images.toModels(),
    owner = UserRef(owner.id, owner.displayName),
    isPublic = public,
    collaborative = collaborative,
    trackCount = tracks?.total ?: 0,
    snapshotId = snapshotId,
    followers = followers?.total ?: 0,
)

fun SimpleShowDto.toModel() = Show(
    id = id.orEmpty(),
    uri = uri.orEmpty(),
    name = name,
    publisher = publisher.orEmpty(),
    images = images.toModels(),
    description = description,
)

fun CategoryDto.toModel() = Category(id = id, name = name, icons = icons.map { it.toModel() })

fun UserDto.toModel() = User(
    id = id,
    displayName = displayName,
    email = email,
    country = country,
    product = when (product?.lowercase()) {
        "premium" -> Product.Premium
        "free" -> Product.Free
        "open" -> Product.Open
        else -> Product.Unknown
    },
    images = images.toModels(),
    followers = followers?.total ?: 0,
)

fun PlayHistoryDto.toModel() = PlayHistoryItem(track = track.toModel(), playedAt = playedAt)

fun DeviceDto.toModel() = Device(
    id = id,
    name = name,
    type = type,
    isActive = isActive,
    isRestricted = isRestricted,
    volumePercent = volumePercent,
)

/** Playlist items start at [offset]; positions are needed for remove/reorder. */
fun PagingDto<PlaylistItemDto>.toPlaylistTracks(): List<PlaylistTrack> =
    items.mapIndexedNotNull { i, item ->
        item.track?.let { PlaylistTrack(position = offset + i, addedAt = item.addedAt, track = it.toModel()) }
    }

fun <T, R> PagingDto<T>.toPage(transform: (T) -> R?): Page<R> = Page(
    items = items.mapNotNull(transform),
    offset = offset,
    total = total,
    nextOffset = if (next != null) offset + items.size else null,
)
