package com.spotineil.feature.player

import androidx.paging.PagingData
import app.cash.turbine.test
import com.spotineil.core.data.repository.LibraryRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.LibraryItem
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.LibrarySort
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.Product
import com.spotineil.core.model.Track
import com.spotineil.core.model.User
import com.spotineil.core.testing.FakePlaybackController
import com.spotineil.core.testing.MainDispatcherRule
import com.spotineil.core.testing.TestData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeLibrary : LibraryRepository {
    val saved = mutableSetOf<String>()
    override fun items(type: LibraryItemType, sort: LibrarySort): Flow<PagingData<LibraryItem>> = emptyFlow()
    override fun likedSongs(): Flow<PagingData<Track>> = emptyFlow()
    override suspend fun likedSongsCount() = saved.size
    override suspend fun areTracksSaved(ids: List<String>) = ids.map { it in saved }
    override suspend fun setTrackSaved(id: String, saved: Boolean) { if (saved) this.saved += id else this.saved -= id }
    override suspend fun isAlbumSaved(id: String) = false
    override suspend fun setAlbumSaved(id: String, saved: Boolean) = Unit
    override suspend fun isFollowingArtist(id: String) = false
    override suspend fun setFollowingArtist(id: String, follow: Boolean) = Unit
    override suspend fun setFollowingPlaylist(id: String, follow: Boolean) = Unit
}

internal class FakeLikedMedia : com.spotineil.core.data.repository.LikedMediaRepository {
    private val items = kotlinx.coroutines.flow.MutableStateFlow<List<com.spotineil.core.model.PlayableMedia>>(emptyList())
    override val liked: kotlinx.coroutines.flow.Flow<List<com.spotineil.core.model.PlayableMedia>> = items
    override fun isLiked(mediaId: String) = items.map { list -> list.any { it.mediaId == mediaId } }
    override suspend fun setLiked(media: com.spotineil.core.model.PlayableMedia, liked: Boolean) {
        items.value = if (liked) items.value + media else items.value.filterNot { it.mediaId == media.mediaId }
    }
    val ids get() = items.value.map { it.mediaId }
}

private class FakeUsers(product: Product) : UserRepository {
    override val currentUser = MutableStateFlow<User?>(User(id = "me", displayName = "Me", product = product))
    override suspend fun refreshCurrentUser() = currentUser.value!!
    override suspend fun cachedCurrentUser() = currentUser.value
    override suspend fun user(id: String) = currentUser.value!!
}

class PlayerViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val player = FakePlaybackController().apply {
        state.value = PlaybackState(source = PlaybackSource.SpotifyAppRemote, track = TestData.nowPlaying, isPlaying = false, positionMs = 42_000)
    }

    @Test
    fun `like state is loaded for the current track and can be toggled`() = runTest {
        val library = FakeLibrary()
        val vm = PlayerViewModel(player, library, FakeLikedMedia(), FakeUsers(Product.Premium), { error("not used") })
        vm.uiState.test {
            var state = awaitItem()
            while (state.isLiked == null) state = awaitItem()
            assertFalse(state.isLiked!!)

            vm.toggleLike()
            while (state.isLiked != true) state = awaitItem()
            assertTrue("t1" in library.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `free songs played on the device can be liked without an account`() = runTest {
        val media = com.spotineil.core.model.PlayableMedia(mediaId = "audius:42", uri = "https://s/42", title = "Free song", artist = "Artist", durationMs = 200_000)
        val local = FakePlaybackController().apply {
            state.value = PlaybackState(
                source = PlaybackSource.Local,
                track = TestData.nowPlaying.copy(uri = media.mediaId, media = media),
                isPlaying = true,
            )
        }
        val liked = FakeLikedMedia()
        val vm = PlayerViewModel(local, FakeLibrary(), liked, FakeUsers(Product.Free), { error("not used") })
        vm.uiState.test {
            var state = awaitItem()
            while (state.isLiked == null) state = awaitItem()
            assertTrue(state.canLike)
            assertFalse(state.isLiked!!)
            vm.toggleLike()
            while (state.isLiked != true) state = awaitItem()
            assertEquals(listOf("audius:42"), liked.ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `free accounts see the friendly notice`() = runTest {
        player.canPlayOnDemand.value = null
        val vm = PlayerViewModel(player, FakeLibrary(), FakeLikedMedia(), FakeUsers(Product.Free), { error("not used") })
        vm.uiState.test {
            var state = awaitItem()
            while (!state.showFreeNotice) state = awaitItem()
            assertTrue(state.showFreeNotice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `controls are forwarded to the playback controller`() {
        val vm = PlayerViewModel(player, FakeLibrary(), FakeLikedMedia(), FakeUsers(Product.Premium), { error("not used") })
        vm.togglePlayPause(); vm.next(); vm.previous(); vm.seekTo(1234); vm.toggleShuffle(); vm.cycleRepeat()
        assertEquals(listOf("toggle", "next", "previous", "seek:1234", "shuffle", "repeat"), player.commands)
    }

    @Test
    fun `position is interpolated while playing and frozen while paused`() = runTest {
        var now = 10_000L
        val playing = PlaybackState(track = TestData.nowPlaying, isPlaying = true, positionMs = 1_000, positionTimestamp = 10_000)
        val ticks = positionFlow(playing, tickMs = 1) { now.also { now += 500 } }.take(3).toList()
        assertEquals(listOf(1_000L, 1_500L, 2_000L), ticks)

        val paused = playing.copy(isPlaying = false)
        assertEquals(listOf(1_000L), positionFlow(paused).toList())
    }
}
