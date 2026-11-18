package com.spotineil.core.network.api

import com.spotineil.core.network.model.LrclibTrackDto
import com.spotineil.core.network.model.TokenResponseDto
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** accounts.spotify.com — PKCE token exchange & refresh. No client secret is ever used. */
interface SpotifyAccountsApi {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeCode(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("client_id") clientId: String,
        @Field("code_verifier") codeVerifier: String,
    ): TokenResponseDto

    /** Blocking variant: called from OkHttp's Authenticator thread. */
    @FormUrlEncoded
    @POST("api/token")
    fun refresh(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
        @Field("client_id") clientId: String,
    ): Call<TokenResponseDto>
}

/** https://lrclib.net/docs */
interface LrclibApi {
    @GET("get")
    suspend fun get(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String?,
        @Query("duration") durationSeconds: Long?,
    ): LrclibTrackDto

    @GET("search")
    suspend fun search(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
    ): List<LrclibTrackDto>
}
