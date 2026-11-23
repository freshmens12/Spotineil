package com.spotineil.core.data.di

import android.content.Context
import androidx.room.Room
import com.spotineil.core.data.auth.EncryptedTokenStore
import com.spotineil.core.data.db.CachedResponseDao
import com.spotineil.core.data.db.LibraryDao
import com.spotineil.core.data.db.LibraryRemoteKeyDao
import com.spotineil.core.data.db.LyricsDao
import com.spotineil.core.data.db.RecentSearchDao
import com.spotineil.core.data.db.SpotineilDatabase
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.data.repository.CatalogRepository
import com.spotineil.core.data.repository.ConnectRepository
import com.spotineil.core.data.repository.DataStoreSettingsRepository
import com.spotineil.core.data.repository.DefaultAuthRepository
import com.spotineil.core.data.repository.DefaultCatalogRepository
import com.spotineil.core.data.repository.DefaultConnectRepository
import com.spotineil.core.data.repository.DefaultHomeRepository
import com.spotineil.core.data.repository.DefaultLibraryRepository
import com.spotineil.core.data.repository.DefaultLyricsRepository
import com.spotineil.core.data.repository.DefaultPlaylistRepository
import com.spotineil.core.data.repository.DefaultSearchRepository
import com.spotineil.core.data.repository.DefaultStatsRepository
import com.spotineil.core.data.repository.DefaultUserRepository
import com.spotineil.core.data.repository.HomeRepository
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.LocalMediaRepository
import com.spotineil.core.data.repository.LyricsRepository
import com.spotineil.core.data.repository.MediaStoreRepository
import com.spotineil.core.data.repository.PlaylistRepository
import com.spotineil.core.data.repository.SearchRepository
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.data.repository.StatsRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.data.innertube.DefaultInnerTubeCatalogRepository
import com.spotineil.core.data.innertube.InnerTubeCatalogRepository
import com.spotineil.core.network.auth.AuthTokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SpotineilDatabase =
        Room.databaseBuilder(context, SpotineilDatabase::class.java, "spotineil.db")
            .fallbackToDestructiveMigration() // it's a cache: safe to rebuild
            .build()

    @Provides fun recentSearchDao(db: SpotineilDatabase): RecentSearchDao = db.recentSearchDao()
    @Provides fun libraryDao(db: SpotineilDatabase): LibraryDao = db.libraryDao()
    @Provides fun libraryRemoteKeyDao(db: SpotineilDatabase): LibraryRemoteKeyDao = db.libraryRemoteKeyDao()
    @Provides fun cachedResponseDao(db: SpotineilDatabase): CachedResponseDao = db.cachedResponseDao()
    @Provides fun lyricsDao(db: SpotineilDatabase): LyricsDao = db.lyricsDao()
    @Provides fun favoriteStationDao(db: SpotineilDatabase): com.spotineil.core.data.db.FavoriteStationDao = db.favoriteStationDao()
    @Provides fun likedMediaDao(db: SpotineilDatabase): com.spotineil.core.data.db.LikedMediaDao = db.likedMediaDao()
    @Provides fun recentItemDao(db: SpotineilDatabase): com.spotineil.core.data.db.RecentItemDao = db.recentItemDao()
    @Provides fun followedPodcastDao(db: SpotineilDatabase): com.spotineil.core.data.db.FollowedPodcastDao = db.followedPodcastDao()
    @Provides fun userPlaylistDao(db: SpotineilDatabase): com.spotineil.core.data.db.UserPlaylistDao = db.userPlaylistDao()
    @Provides fun playLogDao(db: SpotineilDatabase): com.spotineil.core.data.db.PlayLogDao = db.playLogDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun tokenStore(impl: EncryptedTokenStore): AuthTokenStore
    @Binds abstract fun auth(impl: DefaultAuthRepository): AuthRepository
    @Binds abstract fun user(impl: DefaultUserRepository): UserRepository
    @Binds abstract fun home(impl: DefaultHomeRepository): HomeRepository
    @Binds abstract fun search(impl: DefaultSearchRepository): SearchRepository
    @Binds abstract fun library(impl: DefaultLibraryRepository): LibraryRepository
    @Binds abstract fun catalog(impl: DefaultCatalogRepository): CatalogRepository
    @Binds abstract fun playlist(impl: DefaultPlaylistRepository): PlaylistRepository
    @Binds abstract fun connect(impl: DefaultConnectRepository): ConnectRepository
    @Binds abstract fun lyrics(impl: DefaultLyricsRepository): LyricsRepository
    @Binds abstract fun stats(impl: DefaultStatsRepository): StatsRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun localMedia(impl: MediaStoreRepository): LocalMediaRepository
    @Binds abstract fun freeMusic(impl: com.spotineil.core.data.repository.DefaultFreeMusicRepository): com.spotineil.core.data.repository.FreeMusicRepository
    @Binds abstract fun radio(impl: com.spotineil.core.data.repository.DefaultRadioRepository): com.spotineil.core.data.repository.RadioRepository
    @Binds abstract fun liked(impl: com.spotineil.core.data.repository.DefaultLikedMediaRepository): com.spotineil.core.data.repository.LikedMediaRepository
    @Binds abstract fun recents(impl: com.spotineil.core.data.repository.DefaultRecentsRepository): com.spotineil.core.data.repository.RecentsRepository
    @Binds abstract fun podcasts(impl: com.spotineil.core.data.repository.DefaultPodcastRepository): com.spotineil.core.data.repository.PodcastRepository
    @Binds abstract fun archive(impl: com.spotineil.core.data.repository.DefaultArchiveRepository): com.spotineil.core.data.repository.ArchiveRepository
    @Binds abstract fun ccMixter(impl: com.spotineil.core.data.repository.DefaultCcMixterRepository): com.spotineil.core.data.repository.CcMixterRepository
    @Binds abstract fun youTube(impl: com.spotineil.core.data.repository.DefaultYouTubeRepository): com.spotineil.core.data.repository.YouTubeRepository
    @Binds abstract fun lastFm(impl: com.spotineil.core.data.repository.DefaultLastFmRepository): com.spotineil.core.data.repository.LastFmRepository
    @Binds abstract fun downloads(impl: com.spotineil.core.data.repository.SystemDownloadRepository): com.spotineil.core.data.repository.DownloadRepository
    @Binds abstract fun innerTubeCatalog(impl: DefaultInnerTubeCatalogRepository): InnerTubeCatalogRepository
}
