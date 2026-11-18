package com.spotineil.feature.home

import app.cash.turbine.test
import com.spotineil.core.data.repository.HomeRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.HomeFeed
import com.spotineil.core.model.Product
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.User
import com.spotineil.core.testing.FakePlaybackController
import com.spotineil.core.testing.MainDispatcherRule
import com.spotineil.core.testing.TestData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeHome(initial: HomeFeed?, private val fail: Boolean) : HomeRepository {
    override val feed = MutableStateFlow(initial)
    var refreshCount = 0
    override suspend fun refresh() {
        refreshCount++
        if (fail) throw SpotineilException.Network()
        feed.value = HomeFeed(playlists = listOf(TestData.playlist))
    }
}

private class FakeUsers(product: Product = Product.Premium) : UserRepository {
    override val currentUser = MutableStateFlow<User?>(User("me", "Me", product = product))
    override suspend fun refreshCurrentUser() = currentUser.value!!
    override suspend fun cachedCurrentUser() = currentUser.value
    override suspend fun user(id: String) = currentUser.value!!
}

class HomeViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `greeting follows the time of day`() {
        assertEquals(Greeting.Morning, greetingFor(7))
        assertEquals(Greeting.Afternoon, greetingFor(13))
        assertEquals(Greeting.Evening, greetingFor(20))
        assertEquals(Greeting.Night, greetingFor(2))
    }

    @Test
    fun `refresh loads the feed`() = runTest {
        val home = FakeHome(initial = null, fail = false)
        val vm = HomeViewModel(home, FakeUsers(), FakePlaybackController())
        vm.uiState.test {
            var s = awaitItem()
            while (s.feed == null) s = awaitItem()
            assertEquals(listOf("Focus Mix"), s.feed!!.playlists.map { it.name })
            assertEquals(1, home.refreshCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline with no cache shows an error`() = runTest {
        val vm = HomeViewModel(FakeHome(initial = null, fail = true), FakeUsers(), FakePlaybackController())
        vm.uiState.test {
            var s = awaitItem()
            while (s.error == null) s = awaitItem()
            assertTrue(s.error is SpotineilException.Network)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline with cached feed shows cached content without error`() = runTest {
        val cached = HomeFeed(playlists = listOf(TestData.playlist))
        val vm = HomeViewModel(FakeHome(initial = cached, fail = true), FakeUsers(), FakePlaybackController())
        vm.uiState.test {
            var s = awaitItem()
            while (s.isRefreshing || s.feed == null) s = awaitItem()
            assertEquals(cached, s.feed)
            assertNull(s.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `free users see the notice`() = runTest {
        val vm = HomeViewModel(FakeHome(HomeFeed(), fail = false), FakeUsers(Product.Free), FakePlaybackController())
        vm.uiState.test {
            var s = awaitItem()
            while (s.user == null) s = awaitItem()
            assertTrue(s.showFreeNotice)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
