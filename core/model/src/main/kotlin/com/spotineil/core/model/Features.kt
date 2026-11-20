package com.spotineil.core.model

import kotlinx.serialization.Serializable

/** Everything shown on the Home screen. Sections that fail (e.g. 403) are simply empty. */
@Serializable
data class HomeFeed(
    val recentlyPlayed: List<Track> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val newReleases: List<Album> = emptyList(),
    val savedAlbums: List<Album> = emptyList(),
    val shows: List<Show> = emptyList(),
) {
    val isEmpty: Boolean
        get() = recentlyPlayed.isEmpty() && topArtists.isEmpty() && topTracks.isEmpty() &&
            playlists.isEmpty() && newReleases.isEmpty() && savedAlbums.isEmpty() && shows.isEmpty()
}

enum class TimeRange(val apiValue: String) {
    ShortTerm("short_term"),
    MediumTerm("medium_term"),
    LongTerm("long_term"),
}

enum class SearchType(val apiValue: String) {
    Track("track"),
    Artist("artist"),
    Album("album"),
    Playlist("playlist"),
    Show("show"),
}

/** One heterogeneous search hit. */
sealed interface SearchResult {
    val id: String
    data class TrackResult(val track: com.spotineil.core.model.Track) : SearchResult { override val id get() = track.id }
    data class ArtistResult(val artist: com.spotineil.core.model.Artist) : SearchResult { override val id get() = artist.id }
    data class AlbumResult(val album: com.spotineil.core.model.Album) : SearchResult { override val id get() = album.id }
    data class PlaylistResult(val playlist: com.spotineil.core.model.Playlist) : SearchResult { override val id get() = playlist.id }
    data class ShowResult(val show: com.spotineil.core.model.Show) : SearchResult { override val id get() = show.id }
}

@kotlinx.serialization.Serializable
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val shows: List<Show> = emptyList(),
) {
    val isEmpty get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && shows.isEmpty()
}

data class RecentSearch(val query: String, val timestamp: Long)

enum class LibraryItemType { Playlist, Album, Artist, Show }

enum class LibrarySort { Recent, Alphabetical, Creator }

/** A row in the Library tabs; flattened so all kinds share one cache table and sort logic. */
data class LibraryItem(
    val id: String,
    val uri: String,
    val type: LibraryItemType,
    val name: String,
    val subtitle: String,
    val creator: String,
    val imageUrl: String?,
    val addedAt: Long,
)

data class ArtistDetails(
    val artist: Artist,
    val topTracks: List<Track>,
    val albums: List<Album>,
    val singles: List<Album>,
    val isFollowing: Boolean,
)

data class PlaylistDetails(
    val playlist: Playlist,
    val isOwnedByMe: Boolean,
    val isFollowing: Boolean,
)

data class AlbumDetails(val album: Album, val isSaved: Boolean)

/** Spotify Connect device from the Web API. */
data class Device(
    val id: String?,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
    val volumePercent: Int?,
)

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val isInstrumental: Boolean = false,
    val source: String = "LRCLIB",
) {
    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }
}

data class TopItems(
    val range: TimeRange,
    val tracks: List<Track>,
    val artists: List<Artist>,
)

data class LocalTrack(
    val id: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumArtUri: String?,
)
