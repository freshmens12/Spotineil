package com.spotineil.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.spotineil.app.widget.WidgetUpdater
import com.spotineil.core.common.SpotifyAppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class SpotineilApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        widgetUpdater.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun spotifyAppConfig(): SpotifyAppConfig = SpotifyAppConfig(
        clientId = BuildConfig.SPOTIFY_CLIENT_ID,
        redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI,
        scopes = SpotifyAppConfig.SCOPES,
        appVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun freeSourcesConfig(): com.spotineil.core.common.FreeSourcesConfig =
        com.spotineil.core.common.FreeSourcesConfig(
            jamendoClientId = BuildConfig.JAMENDO_CLIENT_ID,
            lastFmApiKey = BuildConfig.LASTFM_API_KEY,
        )
}
