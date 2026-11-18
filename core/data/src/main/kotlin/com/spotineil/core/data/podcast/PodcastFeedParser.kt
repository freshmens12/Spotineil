package com.spotineil.core.data.podcast

import com.spotineil.core.model.Podcast
import com.spotineil.core.model.PodcastDetail
import com.spotineil.core.model.PodcastEpisode
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Parses a podcast RSS 2.0 feed (with iTunes tags) into a [PodcastDetail].
 * Uses SAX (works on Android and plain JVM tests); external entities are disabled.
 */
object PodcastFeedParser {

    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    fun parse(input: InputStream, fallback: Podcast, maxEpisodes: Int = 200): PodcastDetail {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
            ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
        }
        val handler = Handler(maxEpisodes)
        try {
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (_: StopParsing) {
            // Enough episodes.
        }
        val podcast = fallback.copy(
            title = handler.channelTitle?.takeIf { it.isNotBlank() } ?: fallback.title,
            author = handler.channelAuthor?.takeIf { it.isNotBlank() } ?: fallback.author,
            artworkUrl = handler.channelImage ?: fallback.artworkUrl,
        )
        return PodcastDetail(podcast, handler.channelDescription?.let(::stripHtml), handler.episodes)
    }

    private class StopParsing : RuntimeException()

    private class Handler(private val max: Int) : DefaultHandler() {
        var channelTitle: String? = null
        var channelAuthor: String? = null
        var channelDescription: String? = null
        var channelImage: String? = null
        val episodes = mutableListOf<PodcastEpisode>()

        private val text = StringBuilder()
        private var inItem = false
        private var inChannelImage = false

        // Current item
        private var title: String? = null
        private var guid: String? = null
        private var audioUrl: String? = null
        private var pubDate: String? = null
        private var duration: String? = null
        private var description: String? = null
        private var image: String? = null

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            text.setLength(0)
            val name = localName.ifEmpty { qName }
            when {
                name == "item" -> {
                    inItem = true
                    title = null; guid = null; audioUrl = null; pubDate = null; duration = null; description = null; image = null
                }
                name == "enclosure" && inItem -> {
                    val type = attrs.getValue("type").orEmpty()
                    val url = attrs.getValue("url")
                    if (url != null && (type.isEmpty() || type.startsWith("audio") || audioUrl == null)) audioUrl = url
                }
                name == "image" && uri == ITUNES_NS -> {
                    val href = attrs.getValue("href")
                    if (inItem) image = href else if (channelImage == null) channelImage = href
                }
                name == "image" && !inItem -> inChannelImage = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val name = localName.ifEmpty { qName }
            val value = text.toString().trim()
            if (inItem) {
                when {
                    name == "title" && uri != ITUNES_NS -> title = value
                    name == "guid" -> guid = value
                    name == "pubDate" -> pubDate = value
                    name == "duration" && uri == ITUNES_NS -> duration = value
                    name == "description" || (name == "summary" && uri == ITUNES_NS && description == null) ->
                        if (value.isNotEmpty()) description = value
                    name == "item" -> {
                        inItem = false
                        val url = audioUrl
                        if (url != null) {
                            episodes += PodcastEpisode(
                                id = (guid?.takeIf { it.isNotBlank() } ?: url).hashCode().toUInt().toString(16) + "-" + episodes.size,
                                title = title?.takeIf { it.isNotBlank() } ?: "Episode ${episodes.size + 1}",
                                audioUrl = url,
                                publishedAtMs = pubDate?.let(::parseDate),
                                durationMs = duration?.let(::parseDuration) ?: 0L,
                                description = description?.let(::stripHtml)?.take(500),
                                artworkUrl = image,
                            )
                            if (episodes.size >= max) throw StopParsing()
                        }
                    }
                }
            } else {
                when {
                    name == "url" && inChannelImage -> if (channelImage == null && value.isNotEmpty()) channelImage = value
                    name == "image" -> inChannelImage = false
                    name == "title" && uri != ITUNES_NS && channelTitle == null && !inChannelImage -> channelTitle = value
                    name == "author" && uri == ITUNES_NS && channelAuthor == null -> channelAuthor = value
                    name == "description" && channelDescription == null -> channelDescription = value
                    name == "summary" && uri == ITUNES_NS && channelDescription == null -> channelDescription = value
                }
            }
            text.setLength(0)
        }
    }

    /** "3725", "62:05" or "1:02:05" → milliseconds. */
    fun parseDuration(raw: String): Long? {
        val parts = raw.trim().split(':').map { it.trim().toDoubleOrNull() ?: return null }
        val seconds = parts.fold(0.0) { acc, p -> acc * 60 + p }
        return (seconds * 1000).toLong().takeIf { it > 0 }
    }

    fun parseDate(raw: String): Long? = runCatching {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("[ \\t\\x0B\\f\\r]+")

    fun stripHtml(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(SPACES, " ")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
