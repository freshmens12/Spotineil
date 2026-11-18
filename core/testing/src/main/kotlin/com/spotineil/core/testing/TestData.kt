package com.spotineil.core.testing

import com.spotineil.core.model.Album
import com.spotineil.core.model.Artist
import com.spotineil.core.model.Image
import com.spotineil.core.model.NowPlayingTrack
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.SimpleAlbum
import com.spotineil.core.model.SimpleArtist
import com.spotineil.core.model.Track
import com.spotineil.core.model.UserRef

/** Fictional catalog data for tests. */
object TestData {
    val artist = Artist(id = "ar1", uri = "spotify:artist:ar1", name = "The Test Band", images = listOf(Image("https://img/ar1", 640, 640)))
    private val simpleArtist = SimpleArtist("ar1", "spotify:artist:ar1", "The Test Band")
    private val simpleAlbum = SimpleAlbum("al1", "spotify:album:al1", "Debug Sessions", listOf(Image("https://img/al1", 640, 640)))

    fun track(n: Int = 1) = Track(
        id = "t$n",
        uri = "spotify:track:t$n",
        name = "Test Track $n",
        artists = listOf(simpleArtist),
        album = simpleAlbum,
        durationMs = 200_000,
    )

    val album = Album(
        id = "al1",
        uri = "spotify:album:al1",
        name = "Debug Sessions",
        artists = listOf(simpleArtist),
        images = simpleAlbum.images,
        releaseDate = "2024-05-01",
        totalTracks = 2,
        tracks = listOf(track(1), track(2)),
    )

    val playlist = Playlist(
        id = "pl1",
        uri = "spotify:playlist:pl1",
        name = "Focus Mix",
        description = null,
        images = emptyList(),
        owner = UserRef("me", "Me"),
        isPublic = false,
        collaborative = false,
        trackCount = 2,
    )

    val nowPlaying = NowPlayingTrack(
        uri = "spotify:track:t1",
        name = "Test Track 1",
        artists = "The Test Band",
        artistUri = "spotify:artist:ar1",
        album = "Debug Sessions",
        albumUri = "spotify:album:al1",
        durationMs = 200_000,
        imageUrl = null,
    )
}
