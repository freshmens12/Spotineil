package com.spotineil.feature.discover

import app.cash.turbine.test
import com.spotineil.core.data.repository.DownloadRepository
import com.spotineil.core.data.repository.FreeMusicRepository
import com.spotineil.core.data.repository.FreeMusicSections
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.StreamSource
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.testing.FakePlaybackController
import com.spotineil.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun track(id: String, download: Boolean = false) = StreamTrack(
    id = id, source = StreamSource.Audius, title = "Track $id", artist = "Artist", album = null, artworkUrl = null,
    durationMs = 1000, streamUrl = "https://s/$id", downloadUrl = if (download) "https://d/$id" else null,
)

private class FakeFreeMusic : FreeMusicRepository {
    val searches = mutableListOf<String>()
    override suspend fun featured() = FreeMusicSections(listOf(track("1"), track("2")), emptyList(), false)
    override suspend fun search(query: String): FreeMusicSections { searches += query; return FreeMusicSections(listOf(track("q")), emptyList(), false) }
    override suspend fun trending(genre: String?, limit: Int) = listOf(track("g"))
    override suspend fun underground(limit: Int) = listOf(track("u"))
}

private class FakeDownloads : DownloadRepository {
    val downloaded = mutableListOf<String>()
    override fun download(track: StreamTrack): Boolean = track.canDownload.also { if (it) downloaded += track.id }
}

private class FakeRadio : RadioRepository {
    override val favorites = MutableStateFlow<List<RadioStation>>(emptyList())
    val played = mutableListOf<String>()
    var tagQueries = mutableListOf<String>()
    private fun st(id: String) = RadioStation(id, "Station $id", "http://r/$id", null, listOf("pop"), "PH", "PH", "MP3", 128)
    override suspend fun top(limit: Int) = listOf(st("top"))
    override suspend fun byCountry(countryCode: String, limit: Int) = listOf(st("local"))
    override suspend fun byTag(tag: String, limit: Int) = listOf(st("tag")).also { tagQueries += tag }
    override suspend fun search(query: String, limit: Int) = listOf(st("search"))
    override suspend fun setFavorite(station: RadioStation, favorite: Boolean) {
        favorites.value = if (favorite) favorites.value + station else favorites.value - station
    }
    override suspend fun reportPlay(station: RadioStation) { played += station.id }
}

class DiscoverViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()
    private val player = FakePlaybackController()

    @Test
    fun `free music loads featured tracks then debounced search results`() = runTest {
        val repo = FakeFreeMusic()
        val vm = FreeMusicViewModel(repo, FakeDownloads(), player)
        vm.state.test {
            var s = awaitItem()
            while (s !is LoadState.Loaded) s = awaitItem()
            assertEquals(listOf("1", "2"), s.data.audius.map { it.id })

            vm.onQueryChange("lo"); vm.onQueryChange("lofi")
            advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
            var r = awaitItem()
            while (r !is LoadState.Loaded || r.data.audius.first().id != "q") r = awaitItem()
            assertEquals(listOf("lofi"), repo.searches)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing a free track queues the whole section on the device player`() {
        val vm = FreeMusicViewModel(FakeFreeMusic(), FakeDownloads(), player)
        val list = listOf(track("1"), track("2"), track("3"))
        vm.play(list, 1)
        assertEquals(listOf("playMedia:1"), player.commands)
        assertEquals(listOf("audius:1", "audius:2", "audius:3"), player.mediaRequests.single().map { it.mediaId })
    }

    @Test
    fun `download only happens for allowed tracks`() = runTest {
        val downloads = FakeDownloads()
        val vm = FreeMusicViewModel(FakeFreeMusic(), downloads, player)
        vm.messages.test {
            vm.download(track("ok", download = true))
            assertEquals(R.string.discover_download_started, awaitItem())
            vm.download(track("no"))
            assertEquals(R.string.discover_download_failed, awaitItem())
        }
        assertEquals(listOf("ok"), downloads.downloaded)
    }

    @Test
    fun `radio plays a live station, reports the play and supports favorites and tags`() = runTest {
        val radio = FakeRadio()
        val vm = RadioViewModel(radio, player)
        vm.state.test {
            var s = awaitItem()
            while (s !is LoadState.Loaded) s = awaitItem()
            assertEquals(listOf("local"), s.data.local.map { it.id })
            assertEquals(listOf("top"), s.data.top.map { it.id })

            vm.selectTag("jazz")
            var t = awaitItem()
            while (t !is LoadState.Loaded || t.data.results == null) t = awaitItem()
            assertEquals(listOf("jazz"), radio.tagQueries)
            cancelAndIgnoreRemainingEvents()
        }
        val station = radio.top().single()
        vm.play(station)
        assertTrue(player.mediaRequests.single().single().isLive)
        assertEquals(listOf("top"), radio.played)

        vm.favorites.test {
            awaitItem()
            vm.toggleFavorite(station)
            var f = awaitItem()
            while (f.isEmpty()) f = awaitItem()
            assertEquals(listOf("top"), f.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
