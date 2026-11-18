package com.spotineil.core.data

import com.spotineil.core.common.FreeSourcesConfig
import com.spotineil.core.data.db.FavoriteStationDao
import com.spotineil.core.data.db.FavoriteStationEntity
import com.spotineil.core.data.repository.DefaultFreeMusicRepository
import com.spotineil.core.data.repository.DefaultPreviewRepository
import com.spotineil.core.data.repository.DefaultRadioRepository
import com.spotineil.core.model.StreamSource
import com.spotineil.core.network.api.AudiusApi
import com.spotineil.core.network.api.DeezerApi
import com.spotineil.core.network.api.ItunesApi
import com.spotineil.core.network.api.JamendoApi
import com.spotineil.core.network.api.RadioBrowserApi
import com.spotineil.core.network.di.SpotineilJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private class FakeFavoriteDao : FavoriteStationDao {
    private val rows = MutableStateFlow<List<FavoriteStationEntity>>(emptyList())
    override fun observe(): Flow<List<FavoriteStationEntity>> = rows.map { r -> r.sortedByDescending { it.addedAt } }
    override suspend fun upsert(entity: FavoriteStationEntity) { rows.value = rows.value.filterNot { it.id == entity.id } + entity }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
}

class FreeSourcesTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private inline fun <reified T> api(): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(SpotineilJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
    }

    private val audiusJson = """{"data":[
        {"id":"abc","title":"Night Drive","duration":185,"is_streamable":true,"is_downloadable":true,
         "orig_filename":"night drive.wav","artwork":{"480x480":"https://img/480.jpg"},"user":{"name":"Indie Artist","handle":"indie"}},
        {"id":"locked","title":"Gated","duration":100,"is_streamable":false,"user":{"name":"X"}},
        {"id":"nodl","title":"Stream Only","duration":90,"is_streamable":true,"is_downloadable":false,"user":{"name":"","handle":"handle_only"}}
    ]}"""

    @Test
    fun `audius tracks map to streamable full songs and respect download permission`() = runTest {
        route { MockResponse().setBody(audiusJson) }
        val repo = DefaultFreeMusicRepository(api<AudiusApi>(), api<JamendoApi>(), FreeSourcesConfig(jamendoClientId = ""))

        val sections = repo.featured()

        assertEquals(listOf("Night Drive", "Stream Only"), sections.audius.map { it.title }) // non-streamable skipped
        val first = sections.audius.first()
        assertEquals(StreamSource.Audius, first.source)
        assertEquals(185_000L, first.durationMs)
        assertEquals("https://api.audius.co/v1/tracks/abc/stream?app_name=Spotineil", first.streamUrl)
        assertTrue(first.canDownload)
        assertEquals("Indie Artist - Night Drive.wav", first.downloadFileName)
        assertFalse("no download unless the artist allows it", sections.audius[1].canDownload)
        assertEquals("handle_only", sections.audius[1].artist)
        assertFalse(first.isPreview)

        // Jamendo is skipped entirely without a client id.
        assertFalse(sections.jamendoEnabled)
        assertTrue(server.takeRequest().path!!.startsWith("/v1/tracks/trending"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `jamendo is queried with the client id and only allows downloads the licence permits`() = runTest {
        route { req ->
            if (req.path!!.startsWith("/tracks/")) MockResponse().setBody(
                """{"headers":{"status":"success","code":0},"results":[
                  {"id":"1","name":"CC Song","duration":200,"artist_name":"CC Artist","album_image":"https://img/a.jpg",
                   "audio":"https://stream/1.mp3","audiodownload":"https://dl/1.mp3","audiodownload_allowed":true,
                   "license_ccurl":"https://creativecommons.org/licenses/by/3.0/"},
                  {"id":"2","name":"No DL","duration":100,"artist_name":"B","audio":"https://stream/2.mp3",
                   "audiodownload":"https://dl/2.mp3","audiodownload_allowed":false}
                ]}""",
            ) else MockResponse().setBody("""{"data":[]}""")
        }
        val repo = DefaultFreeMusicRepository(api<AudiusApi>(), api<JamendoApi>(), FreeSourcesConfig(jamendoClientId = "key123"))

        val sections = repo.search("chill")

        assertTrue(sections.jamendoEnabled)
        assertEquals(listOf("CC Song", "No DL"), sections.jamendo.map { it.title })
        assertEquals("https://dl/1.mp3", sections.jamendo[0].downloadUrl)
        assertNull(sections.jamendo[1].downloadUrl)
        val jamendoRequest = (1..server.requestCount).map { server.takeRequest() }.first { it.path!!.startsWith("/tracks/") }
        assertEquals("key123", jamendoRequest.requestUrl!!.queryParameter("client_id"))
        assertEquals("chill", jamendoRequest.requestUrl!!.queryParameter("search"))
    }

    @Test
    fun `radio fails over to the next mirror and drops broken stations`() = runTest {
        route { req ->
            when {
                req.path!!.startsWith("/dead") -> MockResponse().setResponseCode(503)
                else -> MockResponse().setBody(
                    """[
                      {"stationuuid":"s1","name":" Good FM ","url_resolved":"http://stream.example/good","tags":"pop,hits,top40,extra","country":"Philippines","countrycode":"PH","bitrate":128,"lastcheckok":1},
                      {"stationuuid":"s2","name":"Broken","url_resolved":"http://x","lastcheckok":0},
                      {"stationuuid":"s3","name":"No URL","url_resolved":"","lastcheckok":1}
                    ]""",
                )
            }
        }
        val repo = DefaultRadioRepository(api<RadioBrowserApi>(), FakeFavoriteDao(), SpotineilJson)
        val base = server.url("/").toString().trimEnd('/')
        repo.hosts = listOf("$base/dead", base)

        val stations = repo.byCountry("PH")

        assertEquals(listOf("s1"), stations.map { it.id })
        val good = stations.single()
        assertEquals("Good FM", good.name)
        assertEquals("pop, hits, top40 • Philippines", good.subtitle)
        assertEquals(2, server.requestCount)
        assertEquals("PH", server.takeRequest().let { server.takeRequest() }.requestUrl!!.queryParameter("countrycode"))
    }

    @Test
    fun `favorite stations are saved and removed`() = runTest {
        route { MockResponse().setBody("""[{"stationuuid":"s1","name":"Fav","url_resolved":"https://s/1","lastcheckok":1}]""") }
        val repo = DefaultRadioRepository(api<RadioBrowserApi>(), FakeFavoriteDao(), SpotineilJson)
        repo.hosts = listOf(server.url("/").toString().trimEnd('/'))
        val station = repo.top().single()

        repo.setFavorite(station, true)
        assertEquals(listOf("Fav"), repo.favorites.first().map { it.name })
        repo.setFavorite(station, false)
        assertTrue(repo.favorites.first().isEmpty())
    }

    @Test
    fun `previews come from Deezer and fall back to iTunes when Deezer has nothing`() = runTest {
        route { req ->
            when {
                req.path!!.startsWith("/search?q=") -> MockResponse().setBody("""{"data":[]}""")
                req.path!!.startsWith("/search?term=") -> MockResponse().setBody(
                    """{"results":[{"trackId":7,"trackName":"Tune","artistName":"Band","artworkUrl100":"https://a/100x100bb.jpg",
                       "previewUrl":"https://p/7.m4a","trackTimeMillis":210000},{"trackId":8,"trackName":"No preview"}]}""",
                )
                req.path!!.startsWith("/chart/") -> MockResponse().setBody(
                    """{"tracks":{"data":[{"id":1,"title":"Hit","duration":200,"preview":"https://p/1.mp3","artist":{"name":"Star"},"album":{"title":"A","cover_xl":"https://c/xl.jpg"}},
                       {"id":2,"title":"Unavailable","preview":"","artist":{"name":"Y"}}]}}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repo = DefaultPreviewRepository(api<DeezerApi>(), api<ItunesApi>())

        val chart = repo.charts()
        assertEquals(listOf("Hit"), chart.map { it.title })
        assertTrue(chart.single().isPreview)
        assertFalse("previews are never downloadable", chart.single().canDownload)

        val results = repo.search("tune")
        assertEquals(listOf("Tune"), results.map { it.title })
        assertEquals(StreamSource.ITunes, results.single().source)
        assertEquals("https://a/600x600bb.jpg", results.single().artworkUrl)
    }
}
