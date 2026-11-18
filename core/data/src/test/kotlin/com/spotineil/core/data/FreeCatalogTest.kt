package com.spotineil.core.data

import com.spotineil.core.data.podcast.PodcastFeedParser
import com.spotineil.core.data.repository.DefaultArchiveRepository
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.CollectionKind
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.StreamSource
import com.spotineil.core.network.api.ArchiveApi
import com.spotineil.core.network.di.SpotineilJson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Synthetic feed: one channel, two episodes (one with HTML in its notes), one item without audio. */
private val FEED = """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
      <channel>
        <title>Test Show</title>
        <itunes:author>Test Host</itunes:author>
        <description>A show used in unit tests.</description>
        <itunes:image href="https://img.example/show.jpg"/>
        <image><url>https://img.example/rss.jpg</url><title>Ignored image title</title></image>
        <item>
          <title>Episode Two</title>
          <guid>ep-2</guid>
          <pubDate>Tue, 10 Nov 2026 08:00:00 GMT</pubDate>
          <itunes:duration>1:02:05</itunes:duration>
          <description><![CDATA[<p>Notes with <b>bold</b> &amp; a link.</p>]]></description>
          <enclosure url="https://cdn.example/ep2.mp3" type="audio/mpeg" length="1"/>
        </item>
        <item>
          <title>Episode One</title>
          <guid>ep-1</guid>
          <itunes:duration>754</itunes:duration>
          <itunes:image href="https://img.example/ep1.jpg"/>
          <enclosure url="https://cdn.example/ep1.mp3" type="audio/mpeg" length="1"/>
        </item>
        <item>
          <title>Text-only post</title>
        </item>
      </channel>
    </rss>
""".trimIndent()

class FreeCatalogTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun archiveApi(): ArchiveApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(SpotineilJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ArchiveApi::class.java)

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    // ---------------- Podcast RSS ----------------

    @Test
    fun `podcast feed is parsed into channel info and playable episodes`() {
        val fallback = Podcast("https://feeds.example/show.xml", "Directory name", "Directory author", null)
        val detail = PodcastFeedParser.parse(FEED.byteInputStream(), fallback)

        assertEquals("Test Show", detail.podcast.title)
        assertEquals("Test Host", detail.podcast.author)
        assertEquals("https://img.example/show.jpg", detail.podcast.artworkUrl)
        assertEquals("A show used in unit tests.", detail.description)

        // The item without an enclosure is skipped.
        assertEquals(listOf("Episode Two", "Episode One"), detail.episodes.map { it.title })
        val two = detail.episodes[0]
        assertEquals("https://cdn.example/ep2.mp3", two.audioUrl)
        assertEquals(((1 * 60 + 2) * 60 + 5) * 1000L, two.durationMs)
        assertNotNull(two.publishedAtMs)
        assertEquals("Notes with bold & a link.", two.description)

        val one = detail.episodes[1]
        assertEquals(754_000L, one.durationMs)
        assertEquals("https://img.example/ep1.jpg", one.artworkUrl)
        assertNull(one.publishedAtMs)
    }

    @Test
    fun `durations in every common feed format are understood`() {
        assertEquals(90_000L, PodcastFeedParser.parseDuration("90"))
        assertEquals(90_000L, PodcastFeedParser.parseDuration("1:30"))
        assertEquals(3_723_000L, PodcastFeedParser.parseDuration("01:02:03"))
        assertNull(PodcastFeedParser.parseDuration("abc"))
    }

    // ---------------- Internet Archive ----------------

    @Test
    fun `archive search maps docs whose fields may be strings or arrays`() = runTest {
        route {
            json(
                """{"response":{"numFound":2,"docs":[
                    {"identifier":"band2026-01-01","title":"Band Live","creator":"Band","date":"2026-01-01T00:00:00Z"},
                    {"identifier":"label042","title":["Compilation"],"creator":["Label A","Label B"]}
                ]}}""",
            )
        }
        val repo = DefaultArchiveRepository(archiveApi())
        val concerts = repo.concerts()

        assertEquals(2, concerts.size)
        assertEquals("Band", concerts[0].creator)
        assertEquals("2026-01-01", concerts[0].date)
        assertEquals(CollectionKind.Concert, concerts[0].kind)
        assertEquals("Label A", concerts[1].creator)
        assertEquals("https://archive.org/services/img/label042", concerts[1].artworkUrl)
        val q = server.takeRequest().requestUrl!!
        assertTrue(q.queryParameter("q")!!.contains("collection:etree"))
    }

    @Test
    fun `archive item keeps one mp3 per song in track order and skips low-bitrate copies`() = runTest {
        route {
            json(
                """{"metadata":{"identifier":"show1","title":"Show One","creator":"Band","date":"2025-05-05","description":"<p>Set list</p>"},
                    "files":[
                      {"name":"t02.mp3","format":"VBR MP3","length":"125.5","title":"Second","track":"2"},
                      {"name":"t01.mp3","format":"VBR MP3","length":"03:00","title":"First","track":"1/2"},
                      {"name":"t01_64kb.mp3","format":"64Kbps MP3","title":"First"},
                      {"name":"t01.flac","format":"Flac","title":"First"},
                      {"name":"cover.jpg","format":"JPEG"}
                    ]}""",
            )
        }
        val repo = DefaultArchiveRepository(archiveApi())
        val detail = repo.detail(ArchiveCollection("show1", "show1", "Unknown", kind = CollectionKind.Concert))

        assertEquals("Show One", detail.collection.title)
        assertEquals("Set list", detail.description)
        assertEquals(listOf("First", "Second"), detail.tracks.map { it.title })
        val first = detail.tracks[0]
        assertEquals(StreamSource.Archive, first.source)
        assertEquals(180_000L, first.durationMs)
        assertEquals(125_500L, detail.tracks[1].durationMs)
        assertEquals("https://archive.org/download/show1/t01.mp3", first.streamUrl)
        assertTrue(first.canDownload)
        assertEquals("Band", first.artist)
    }
}
