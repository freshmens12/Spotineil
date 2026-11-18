package com.spotineil.core.data

import com.spotineil.core.common.FreeSourcesConfig
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.data.db.CachedResponseDao
import com.spotineil.core.data.db.CachedResponseEntity
import com.spotineil.core.data.repository.DefaultYouTubeRepository
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.YouTubeTitles
import com.spotineil.core.network.api.YouTubeApi
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.di.SpotineilJson
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private class MemoryCacheDao : CachedResponseDao {
    private val rows = MutableStateFlow<Map<String, CachedResponseEntity>>(emptyMap())
    override fun observe(key: String): Flow<CachedResponseEntity?> = rows.map { it[key] }
    override suspend fun get(key: String): CachedResponseEntity? = rows.value[key]
    override suspend fun upsert(entity: CachedResponseEntity) { rows.value = rows.value + (entity.key to entity) }
    override suspend fun delete(key: String) { rows.value = rows.value - key }
}

private fun video(id: String, duration: String = "PT3M30S", embeddable: Boolean = true, live: String = "none") = """
    {"id":"$id","snippet":{"title":"Artist $id - Song $id (Official Video)","channelTitle":"Artist $id VEVO","liveBroadcastContent":"$live",
     "thumbnails":{"high":{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg"}}},
     "contentDetails":{"duration":"$duration"},"status":{"embeddable":$embeddable,"privacyStatus":"public"}}
""".trimIndent()

class YouTubeGatewayTest {

    private lateinit var server: MockWebServer
    private val searchCalls = AtomicInteger()
    private val videoCalls = AtomicInteger()
    private var quotaExceeded = false

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (quotaExceeded) {
                    return MockResponse().setResponseCode(403)
                        .setBody("""{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}""")
                }
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/search") -> {
                        searchCalls.incrementAndGet()
                        MockResponse().setBody("""{"items":[{"id":{"videoId":"a"}},{"id":{"videoId":"short"}},{"id":{"videoId":"b"}},{"id":{"videoId":"live"}}]}""")
                    }
                    path.endsWith("/videos") -> {
                        videoCalls.incrementAndGet()
                        val items = if (request.requestUrl!!.queryParameter("chart") != null) {
                            listOf(video("c1"), video("c2"), video("mix", duration = "PT1H2M"))
                        } else {
                            listOf(video("b"), video("a"), video("short", duration = "PT45S"), video("live", live = "live"))
                        }
                        MockResponse().setBody("""{"items":[${items.joinToString(",")}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }.setHeader("Content-Type", "application/json")
            }
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun repo(budget: Int = 9_000): DefaultYouTubeRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(SpotineilJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YouTubeApi::class.java)
        val cache = JsonCache(MemoryCacheDao(), SpotineilJson, object : Clock { override fun now() = System.currentTimeMillis() })
        return DefaultYouTubeRepository(api, cache, FreeSourcesConfig(jamendoClientId = "", youTubeApiKey = "test-key", youTubeDailyBudget = budget))
    }

    @Test
    fun `search keeps only full-length embeddable songs, in search order`() = runTest {
        val results = repo().search("Some Query")
        // "short" (45 s) and "live" are dropped; order follows search, not the videos call.
        assertEquals(listOf("a", "b"), results.map { it.id })
        assertEquals("Song a", results[0].songTitle)
        assertEquals("Artist a", results[0].artist)
        assertEquals(210_000L, results[0].durationMs)
    }

    @Test
    fun `chart skips hour-long mixes`() = runTest {
        assertEquals(listOf("c1", "c2"), repo().trending("PH").map { it.id })
    }

    @Test
    fun `repeated and simultaneous searches hit the network once`() = runTest {
        val r = repo()
        listOf(async { r.search("hello") }, async { r.search("Hello ") }, async { r.search("hello") }).awaitAll()
        r.search("HELLO")
        assertEquals(1, searchCalls.get())
        assertEquals(1, videoCalls.get())
    }

    @Test
    fun `the daily budget is counted and expensive searches stop before it runs out`() = runTest {
        val r = repo(budget = 250)
        r.search("one") // 101 units
        r.search("two") // 202 units
        assertEquals(202, r.quota.value!!.usedUnits)
        try {
            r.search("three") // would exceed 250
            fail("expected QuotaExceeded")
        } catch (e: SpotineilException.QuotaExceeded) {
            // expected
        }
        assertEquals(2, searchCalls.get())
        // Cheap chart calls still fit.
        assertEquals(2, r.trending("PH").size)
    }

    @Test
    fun `a quota-exceeded answer serves the cached list and marks the day as used up`() = runTest {
        val r = repo()
        val first = r.trending("PH")
        quotaExceeded = true
        try {
            r.search("new query")
            fail("expected QuotaExceeded")
        } catch (e: SpotineilException.QuotaExceeded) {
            // expected
        }
        assertTrue(r.quota.value!!.exhausted)
        // Cached chart is still served without the network.
        assertEquals(first, r.trending("PH"))
    }

    @Test
    fun `music video titles are split into artist and song`() {
        assertEquals("Na Para Bang", YouTubeTitles.song("Mariah - Na Para Bang (Official Music Video)", "Mariah"))
        assertEquals("Mariah", YouTubeTitles.artist("Mariah - Na Para Bang (Official Music Video)", "Mariah"))
        // No separator: the channel (minus "VEVO"/"- Topic") is the artist.
        assertEquals("Song Title", YouTubeTitles.song("Song Title [Lyric Video]", "Band - Topic"))
        assertEquals("Band", YouTubeTitles.artist("Song Title [Lyric Video]", "Band - Topic"))
    }
}
