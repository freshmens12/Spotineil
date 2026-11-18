package com.spotineil.core.network.api

import com.spotineil.core.network.model.AddTracksBody
import com.spotineil.core.network.model.AlbumDto
import com.spotineil.core.network.model.ArtistDto
import com.spotineil.core.network.model.CategoriesResponseDto
import com.spotineil.core.network.model.CreatePlaylistBody
import com.spotineil.core.network.model.CursorPagingDto
import com.spotineil.core.network.model.DevicesDto
import com.spotineil.core.network.model.FollowedArtistsDto
import com.spotineil.core.network.model.IdsBody
import com.spotineil.core.network.model.NewReleasesDto
import com.spotineil.core.network.model.PagingDto
import com.spotineil.core.network.model.PlayBody
import com.spotineil.core.network.model.PlayHistoryDto
import com.spotineil.core.network.model.PlaybackStateDto
import com.spotineil.core.network.model.PlaylistDto
import com.spotineil.core.network.model.PlaylistItemDto
import com.spotineil.core.network.model.QueueDto
import com.spotineil.core.network.model.RemoveTracksBody
import com.spotineil.core.network.model.ReorderTracksBody
import com.spotineil.core.network.model.SavedAlbumDto
import com.spotineil.core.network.model.SavedShowDto
import com.spotineil.core.network.model.SavedTrackDto
import com.spotineil.core.network.model.SearchResponseDto
import com.spotineil.core.network.model.SnapshotDto
import com.spotineil.core.network.model.TopTracksDto
import com.spotineil.core.network.model.TrackDto
import com.spotineil.core.network.model.TransferBody
import com.spotineil.core.network.model.UpdatePlaylistBody
import com.spotineil.core.network.model.UserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Spotify Web API. Only endpoints that are generally available to new apps are used for
 * core features; restricted ones (recommendations, related artists, audio features,
 * featured/category playlists) are intentionally absent.
 */
interface SpotifyApi {

    // ---- User ----
    @GET("me")
    suspend fun me(): UserDto

    @GET("users/{id}")
    suspend fun user(@Path("id") id: String): UserDto

    @GET("me/player/recently-played")
    suspend fun recentlyPlayed(@Query("limit") limit: Int = 20): CursorPagingDto<PlayHistoryDto>

    @GET("me/top/tracks")
    suspend fun topTracks(
        @Query("time_range") timeRange: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): PagingDto<TrackDto>

    @GET("me/top/artists")
    suspend fun topArtists(
        @Query("time_range") timeRange: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): PagingDto<ArtistDto>

    // ---- Library ----
    @GET("me/playlists")
    suspend fun myPlaylists(@Query("limit") limit: Int, @Query("offset") offset: Int): PagingDto<PlaylistDto>

    @GET("me/albums")
    suspend fun savedAlbums(@Query("limit") limit: Int, @Query("offset") offset: Int): PagingDto<SavedAlbumDto>

    @GET("me/tracks")
    suspend fun savedTracks(@Query("limit") limit: Int, @Query("offset") offset: Int): PagingDto<SavedTrackDto>

    @GET("me/shows")
    suspend fun savedShows(@Query("limit") limit: Int, @Query("offset") offset: Int): PagingDto<SavedShowDto>

    @GET("me/following")
    suspend fun followedArtists(
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int,
        @Query("after") after: String?,
    ): FollowedArtistsDto

    @GET("me/tracks/contains")
    suspend fun containsSavedTracks(@Query("ids") ids: String): List<Boolean>

    @PUT("me/tracks")
    suspend fun saveTracks(@Body body: IdsBody)

    @HTTP(method = "DELETE", path = "me/tracks", hasBody = true)
    suspend fun removeTracks(@Body body: IdsBody)

    @GET("me/albums/contains")
    suspend fun containsSavedAlbums(@Query("ids") ids: String): List<Boolean>

    @PUT("me/albums")
    suspend fun saveAlbums(@Body body: IdsBody)

    @HTTP(method = "DELETE", path = "me/albums", hasBody = true)
    suspend fun removeAlbums(@Body body: IdsBody)

    @GET("me/following/contains")
    suspend fun isFollowingArtists(@Query("type") type: String = "artist", @Query("ids") ids: String): List<Boolean>

    @PUT("me/following")
    suspend fun followArtists(@Query("type") type: String = "artist", @Body body: IdsBody)

    @HTTP(method = "DELETE", path = "me/following", hasBody = true)
    suspend fun unfollowArtists(@Query("type") type: String = "artist", @Body body: IdsBody)

    @PUT("playlists/{id}/followers")
    suspend fun followPlaylist(@Path("id") id: String)

    @DELETE("playlists/{id}/followers")
    suspend fun unfollowPlaylist(@Path("id") id: String)

    @GET("playlists/{id}/followers/contains")
    suspend fun isFollowingPlaylist(@Path("id") id: String, @Query("ids") userId: String): List<Boolean>

    // ---- Browse / search ----
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") types: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
    ): SearchResponseDto

    @GET("browse/categories")
    suspend fun categories(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0): CategoriesResponseDto

    @GET("browse/new-releases")
    suspend fun newReleases(@Query("limit") limit: Int = 20, @Query("offset") offset: Int = 0): NewReleasesDto

    // ---- Catalog ----
    @GET("albums/{id}")
    suspend fun album(@Path("id") id: String): AlbumDto

    @GET("albums/{id}/tracks")
    suspend fun albumTracks(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PagingDto<TrackDto>

    @GET("artists/{id}")
    suspend fun artist(@Path("id") id: String): ArtistDto

    @GET("artists/{id}/top-tracks")
    suspend fun artistTopTracks(@Path("id") id: String, @Query("market") market: String = "from_token"): TopTracksDto

    @GET("artists/{id}/albums")
    suspend fun artistAlbums(
        @Path("id") id: String,
        @Query("include_groups") groups: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): PagingDto<AlbumDto>

    @GET("playlists/{id}")
    suspend fun playlist(
        @Path("id") id: String,
        @Query("fields") fields: String =
            "id,name,uri,description,images,owner(id,display_name),public,collaborative,tracks(total),snapshot_id,followers(total)",
    ): PlaylistDto

    @GET("playlists/{id}/tracks")
    suspend fun playlistItems(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PagingDto<PlaylistItemDto>

    // ---- Playlist management ----
    @POST("users/{userId}/playlists")
    suspend fun createPlaylist(@Path("userId") userId: String, @Body body: CreatePlaylistBody): PlaylistDto

    @PUT("playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: String, @Body body: UpdatePlaylistBody)

    @POST("playlists/{id}/tracks")
    suspend fun addToPlaylist(@Path("id") id: String, @Body body: AddTracksBody): SnapshotDto

    @HTTP(method = "DELETE", path = "playlists/{id}/tracks", hasBody = true)
    suspend fun removeFromPlaylist(@Path("id") id: String, @Body body: RemoveTracksBody): SnapshotDto

    @PUT("playlists/{id}/tracks")
    suspend fun reorderPlaylist(@Path("id") id: String, @Body body: ReorderTracksBody): SnapshotDto

    // ---- Spotify Connect (Web API player; Premium only) ----
    @GET("me/player")
    suspend fun playbackState(@Query("additional_types") types: String = "track,episode"): Response<PlaybackStateDto>

    @GET("me/player/devices")
    suspend fun devices(): DevicesDto

    @PUT("me/player")
    suspend fun transferPlayback(@Body body: TransferBody)

    @PUT("me/player/play")
    suspend fun play(@Query("device_id") deviceId: String? = null, @Body body: PlayBody? = null)

    @PUT("me/player/pause")
    suspend fun pause(@Query("device_id") deviceId: String? = null)

    @POST("me/player/next")
    suspend fun next(@Query("device_id") deviceId: String? = null)

    @POST("me/player/previous")
    suspend fun previous(@Query("device_id") deviceId: String? = null)

    @PUT("me/player/seek")
    suspend fun seek(@Query("position_ms") positionMs: Long, @Query("device_id") deviceId: String? = null)

    @PUT("me/player/shuffle")
    suspend fun shuffle(@Query("state") state: Boolean, @Query("device_id") deviceId: String? = null)

    @PUT("me/player/repeat")
    suspend fun repeat(@Query("state") state: String, @Query("device_id") deviceId: String? = null)

    @PUT("me/player/volume")
    suspend fun volume(@Query("volume_percent") percent: Int, @Query("device_id") deviceId: String? = null)

    @POST("me/player/queue")
    suspend fun addToQueue(@Query("uri") uri: String, @Query("device_id") deviceId: String? = null)

    @GET("me/player/queue")
    suspend fun queue(): QueueDto
}
