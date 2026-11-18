package com.spotineil.core.data.repository

import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.model.HomeFeed
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.TimeRange
import com.spotineil.core.model.TopItems
import com.spotineil.core.model.User
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.model.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /** Cached profile of the logged-in user (null until first fetch). */
    val currentUser: Flow<User?>
    suspend fun refreshCurrentUser(): User
    suspend fun cachedCurrentUser(): User?
    suspend fun user(id: String): User
}

@Singleton
class DefaultUserRepository @Inject constructor(
    private val api: SpotifyApi,
    private val cache: JsonCache,
) : UserRepository {
    override val currentUser: Flow<User?> = cache.observe(KEY_ME, User.serializer())

    override suspend fun refreshCurrentUser(): User {
        val user = apiCall { api.me() }.toModel()
        cache.put(KEY_ME, User.serializer(), user)
        return user
    }

    override suspend fun cachedCurrentUser(): User? = cache.get(KEY_ME, User.serializer())

    override suspend fun user(id: String): User = apiCall { api.user(id) }.toModel()

    private companion object { const val KEY_ME = "me" }
}

interface HomeRepository {
    val feed: Flow<HomeFeed?>

    /**
     * Fetches all sections in parallel. Sections unavailable to this app (403/404) are left
     * empty. Throws only if *every* section failed, so a partially-working API still renders.
     */
    suspend fun refresh()
}

@Singleton
class DefaultHomeRepository @Inject constructor(
    private val api: SpotifyApi,
    private val cache: JsonCache,
) : HomeRepository {

    override val feed: Flow<HomeFeed?> = cache.observe(KEY, HomeFeed.serializer())

    override suspend fun refresh() = coroutineScope {
        val recent = async { section { api.recentlyPlayed(limit = 30).items.map { it.track.toModel() }.distinctBy { it.id } } }
        val topArtists = async { section { api.topArtists(TimeRange.ShortTerm.apiValue, limit = 12).items.map { it.toModel() } } }
        val topTracks = async { section { api.topTracks(TimeRange.ShortTerm.apiValue, limit = 12).items.map { it.toModel() } } }
        val playlists = async { section { api.myPlaylists(limit = 20, offset = 0).items.map { it.toModel() } } }
        val releases = async { section { api.newReleases(limit = 20).albums.items.map { it.toModel() } } }
        val saved = async { section { api.savedAlbums(limit = 20, offset = 0).items.map { it.album.toModel() } } }

        val shows = async { section { api.savedShows(limit = 20, offset = 0).items.map { it.show.toModel() } } }

        val results = listOf(recent, topArtists, topTracks, playlists, releases, saved, shows).map { it.await() }
        val failures = results.mapNotNull { it.exceptionOrNull() }
        if (failures.size == results.size) throw failures.first()

        val feed = HomeFeed(
            recentlyPlayed = recent.await().getOrDefault(emptyList()),
            topArtists = topArtists.await().getOrDefault(emptyList()),
            topTracks = topTracks.await().getOrDefault(emptyList()),
            playlists = playlists.await().getOrDefault(emptyList()),
            newReleases = releases.await().getOrDefault(emptyList()),
            savedAlbums = saved.await().getOrDefault(emptyList()),
            shows = shows.await().getOrDefault(emptyList()),
        )
        cache.put(KEY, HomeFeed.serializer(), feed)
    }

    private suspend fun <T> section(block: suspend () -> T): Result<T> = try {
        Result.success(apiCall(block))
    } catch (e: SpotineilException) {
        Result.failure(e)
    }

    private companion object { const val KEY = "home_feed" }
}

interface StatsRepository {
    suspend fun top(range: TimeRange): TopItems
}

@Singleton
class DefaultStatsRepository @Inject constructor(private val api: SpotifyApi) : StatsRepository {
    override suspend fun top(range: TimeRange): TopItems = coroutineScope {
        val tracks = async { apiCall { api.topTracks(range.apiValue, limit = 50) }.items.map { it.toModel() } }
        val artists = async { apiCall { api.topArtists(range.apiValue, limit = 50) }.items.map { it.toModel() } }
        TopItems(range, tracks.await(), artists.await())
    }
}
