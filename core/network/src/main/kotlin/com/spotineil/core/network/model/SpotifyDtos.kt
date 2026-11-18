package com.spotineil.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PagingDto<T>(
    val items: List<T> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
    val next: String? = null,
)

@Serializable
data class CursorsDto(val after: String? = null, val before: String? = null)

@Serializable
data class CursorPagingDto<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val cursors: CursorsDto? = null,
    val limit: Int = 0,
    val total: Int? = null,
)

@Serializable
data class ImageDto(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class FollowersDto(val total: Int = 0)

@Serializable
data class SimpleArtistDto(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
)

@Serializable
data class SimpleAlbumDto(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
    val images: List<ImageDto>? = null,
    val artists: List<SimpleArtistDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("album_type") val albumType: String? = null,
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
    val artists: List<SimpleArtistDto> = emptyList(),
    val album: SimpleAlbumDto? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val explicit: Boolean = false,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("is_local") val isLocal: Boolean = false,
    // Episode-only fields (playlist / queue items may be podcast episodes)
    val type: String? = null,
    val images: List<ImageDto>? = null,
    val show: SimpleShowDto? = null,
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String = "",
    val uri: String = "",
    val images: List<ImageDto>? = null,
    val artists: List<SimpleArtistDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("album_type") val albumType: String? = null,
    val label: String? = null,
    val tracks: PagingDto<TrackDto>? = null,
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String = "",
    val uri: String = "",
    val images: List<ImageDto>? = null,
    val genres: List<String> = emptyList(),
    val followers: FollowersDto? = null,
)

@Serializable
data class OwnerDto(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class TracksRefDto(val total: Int = 0)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String = "",
    val uri: String = "",
    val description: String? = null,
    val images: List<ImageDto>? = null,
    val owner: OwnerDto = OwnerDto(),
    val public: Boolean? = null,
    val collaborative: Boolean = false,
    val tracks: TracksRefDto? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val followers: FollowersDto? = null,
)

@Serializable
data class PlaylistItemDto(
    @SerialName("added_at") val addedAt: String? = null,
    val track: TrackDto? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
)

@Serializable
data class SavedAlbumDto(@SerialName("added_at") val addedAt: String? = null, val album: AlbumDto)

@Serializable
data class SavedTrackDto(@SerialName("added_at") val addedAt: String? = null, val track: TrackDto)

@Serializable
data class SimpleShowDto(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
    val publisher: String? = null,
    val images: List<ImageDto>? = null,
    val description: String? = null,
)

@Serializable
data class SavedShowDto(@SerialName("added_at") val addedAt: String? = null, val show: SimpleShowDto)

@Serializable
data class PlayHistoryDto(val track: TrackDto, @SerialName("played_at") val playedAt: String = "")

@Serializable
data class UserDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    val country: String? = null,
    val product: String? = null,
    val images: List<ImageDto>? = null,
    val followers: FollowersDto? = null,
)

@Serializable
data class SearchResponseDto(
    val tracks: PagingDto<TrackDto?>? = null,
    val artists: PagingDto<ArtistDto?>? = null,
    val albums: PagingDto<AlbumDto?>? = null,
    // Spotify is known to return null entries in these lists.
    val playlists: PagingDto<PlaylistDto?>? = null,
    val shows: PagingDto<SimpleShowDto?>? = null,
)

@Serializable
data class CategoryDto(val id: String, val name: String = "", val icons: List<ImageDto> = emptyList())

@Serializable
data class CategoriesResponseDto(val categories: PagingDto<CategoryDto>)

@Serializable
data class NewReleasesDto(val albums: PagingDto<AlbumDto>)

@Serializable
data class TopTracksDto(val tracks: List<TrackDto> = emptyList())

@Serializable
data class FollowedArtistsDto(val artists: CursorPagingDto<ArtistDto>)

@Serializable
data class DeviceDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    @SerialName("volume_percent") val volumePercent: Int? = null,
)

@Serializable
data class DevicesDto(val devices: List<DeviceDto> = emptyList())

@Serializable
data class PlaybackStateDto(
    val device: DeviceDto? = null,
    @SerialName("repeat_state") val repeatState: String = "off",
    @SerialName("shuffle_state") val shuffleState: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val item: TrackDto? = null,
    val timestamp: Long = 0,
)

@Serializable
data class QueueDto(
    @SerialName("currently_playing") val currentlyPlaying: TrackDto? = null,
    val queue: List<TrackDto> = emptyList(),
)

@Serializable
data class SnapshotDto(@SerialName("snapshot_id") val snapshotId: String)

// ---- Request bodies ----

@Serializable
data class CreatePlaylistBody(
    val name: String,
    val description: String? = null,
    val public: Boolean = false,
    val collaborative: Boolean = false,
)

@Serializable
data class UpdatePlaylistBody(
    val name: String? = null,
    val description: String? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
)

@Serializable
data class AddTracksBody(val uris: List<String>, val position: Int? = null)

@Serializable
data class UriRef(val uri: String, val positions: List<Int>? = null)

@Serializable
data class RemoveTracksBody(
    val tracks: List<UriRef>,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class ReorderTracksBody(
    @SerialName("range_start") val rangeStart: Int,
    @SerialName("insert_before") val insertBefore: Int,
    @SerialName("range_length") val rangeLength: Int = 1,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class IdsBody(val ids: List<String>)

@Serializable
data class OffsetBody(val position: Int? = null, val uri: String? = null)

@Serializable
data class PlayBody(
    @SerialName("context_uri") val contextUri: String? = null,
    val uris: List<String>? = null,
    val offset: OffsetBody? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
)

@Serializable
data class TransferBody(@SerialName("device_ids") val deviceIds: List<String>, val play: Boolean = true)

@Serializable
data class SpotifyErrorEnvelope(val error: SpotifyErrorDto? = null)

@Serializable
data class SpotifyErrorDto(val status: Int = 0, val message: String? = null, val reason: String? = null)

// ---- Accounts ----

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

// ---- LRCLIB ----

@Serializable
data class LrclibTrackDto(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
