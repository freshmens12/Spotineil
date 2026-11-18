package com.spotineil.core.network.di

import android.content.Context
import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.network.BuildConfig
import com.spotineil.core.network.RateLimitInterceptor
import com.spotineil.core.network.api.AudiusApi
import com.spotineil.core.network.api.DeezerApi
import com.spotineil.core.network.api.ItunesApi
import com.spotineil.core.network.api.JamendoApi
import com.spotineil.core.network.api.LrclibApi
import com.spotineil.core.network.api.RadioBrowserApi
import com.spotineil.core.network.api.SpotifyAccountsApi
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.auth.AuthInterceptor
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.auth.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpotifyClient

val SpotineilJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = SpotineilJson

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    @Provides
    @Singleton
    fun httpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024)

    @Provides
    @Singleton
    @BaseClient
    fun baseClient(cache: Cache, config: SpotifyAppConfig): OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Spotineil/${config.appVersion} (Android; unofficial client)")
                    .build(),
            )
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    @SpotifyClient
    fun spotifyClient(
        @BaseClient base: OkHttpClient,
        authInterceptor: AuthInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(authInterceptor)
        .authenticator(authenticator)
        .build()

    @Provides
    @Singleton
    fun spotifyApi(@SpotifyClient client: OkHttpClient, json: Json): SpotifyApi = Retrofit.Builder()
        .baseUrl(BuildConfig.SPOTIFY_API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SpotifyApi::class.java)

    @Provides
    @Singleton
    fun accountsApi(@BaseClient client: OkHttpClient, json: Json): SpotifyAccountsApi = Retrofit.Builder()
        .baseUrl(BuildConfig.SPOTIFY_ACCOUNTS_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SpotifyAccountsApi::class.java)

    private inline fun <reified T> freeApi(baseUrl: String, client: OkHttpClient, json: Json): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    @Provides @Singleton
    fun audiusApi(@BaseClient client: OkHttpClient, json: Json): AudiusApi = freeApi("https://api.audius.co/", client, json)

    @Provides @Singleton
    fun jamendoApi(@BaseClient client: OkHttpClient, json: Json): JamendoApi = freeApi("https://api.jamendo.com/v3.0/", client, json)

    @Provides @Singleton
    fun radioBrowserApi(@BaseClient client: OkHttpClient, json: Json): RadioBrowserApi =
        freeApi("https://de1.api.radio-browser.info/", client, json)

    @Provides @Singleton
    fun deezerApi(@BaseClient client: OkHttpClient, json: Json): DeezerApi = freeApi("https://api.deezer.com/", client, json)

    @Provides @Singleton
    fun itunesApi(@BaseClient client: OkHttpClient, json: Json): ItunesApi = freeApi("https://itunes.apple.com/", client, json)

    @Provides @Singleton
    fun applePodcastsApi(@BaseClient client: OkHttpClient, json: Json): com.spotineil.core.network.api.ApplePodcastsApi =
        freeApi("https://itunes.apple.com/", client, json)

    @Provides @Singleton
    fun archiveApi(@BaseClient client: OkHttpClient, json: Json): com.spotineil.core.network.api.ArchiveApi =
        freeApi("https://archive.org/", client, json)

    @Provides @Singleton
    fun ccMixterApi(@BaseClient client: OkHttpClient, json: Json): com.spotineil.core.network.api.CcMixterApi =
        freeApi("https://ccmixter.org/", client, json)

    /**
     * YouTube client: shares the base client's connection pool and cache, sends the app-identity
     * headers for key restrictions, and drops the debug logger so the API key never lands in logs.
     */
    @Provides @Singleton
    fun youTubeApi(@ApplicationContext context: Context, @BaseClient base: OkHttpClient, json: Json): com.spotineil.core.network.api.YouTubeApi {
        val client = base.newBuilder()
            .apply { interceptors().removeAll { it is HttpLoggingInterceptor } }
            .addInterceptor(com.spotineil.core.network.AndroidAppIdentityInterceptor(context))
            .build()
        return freeApi("https://www.googleapis.com/youtube/v3/", client, json)
    }

    @Provides @Singleton
    fun lastFmApi(@BaseClient client: OkHttpClient, json: Json): com.spotineil.core.network.api.LastFmApi =
        freeApi("https://ws.audioscrobbler.com/", client, json)

    @Provides
    @Singleton
    fun lrclibApi(@BaseClient client: OkHttpClient, json: Json): LrclibApi = Retrofit.Builder()
        .baseUrl(BuildConfig.LRCLIB_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LrclibApi::class.java)
}
