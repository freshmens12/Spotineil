package com.spotineil.core.data

import com.spotineil.core.data.innertube.suggestionTexts
import com.spotineil.core.data.repository.AppleMotionArtworkRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewFeaturesParsingTest {

    @Test
    fun searchSuggestionsAreReadInOrderWithoutRepeats() {
        val answer = Json.parseToJsonElement(
            """
            {"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
              {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"taylor sw"},{"text":"ift","bold":true}]},
                "navigationEndpoint":{"searchEndpoint":{"query":"taylor swift"}}}},
              {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"taylor sw"},{"text":"ift songs","bold":true}]}}},
              {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"taylor swift"}]},
                "navigationEndpoint":{"searchEndpoint":{"query":"taylor swift"}}}}
            ]}},{"searchSuggestionsSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":{}}]}}]}
            """.trimIndent(),
        )
        assertEquals(listOf("taylor swift", "taylor swift songs"), suggestionTexts(answer))
    }

    @Test
    fun theSongsAlbumIsFoundAndTheNamedAlbumComesFirst() {
        val answer = Json.parseToJsonElement(
            """
            {"results":[
              {"trackName":"Anti-Hero","artistName":"Taylor Swift","collectionId":1,"collectionName":"Anti-Hero - Single","collectionViewUrl":"https://music.apple.com/us/album/anti-hero-single/1?i=2&uo=4"},
              {"trackName":"Anti-Hero","artistName":"Taylor Swift","collectionId":3,"collectionName":"Midnights","collectionViewUrl":"https://music.apple.com/us/album/midnights/3?i=4&uo=4"},
              {"trackName":"Anti-Hero (Cover)","artistName":"Somebody Else","collectionId":5,"collectionName":"Covers","collectionViewUrl":"https://music.apple.com/us/album/covers/5"},
              {"trackName":"Lavender Haze","artistName":"Taylor Swift","collectionId":3,"collectionName":"Midnights","collectionViewUrl":"https://music.apple.com/us/album/midnights/3"}
            ]}
            """.trimIndent(),
        ) as JsonObject
        val albums = AppleMotionArtworkRepository.matchingAlbums(answer, "Anti-Hero (Official Music Video)", "Taylor Swift, Someone", album = null)
        assertEquals(listOf("https://music.apple.com/us/album/midnights/3", "https://music.apple.com/us/album/anti-hero-single/1"), albums)
    }

    @Test
    fun theSquareCoverVideoIsTakenFromTheAlbumPage() {
        val page = """
            ..."editorialVideo":{"motionDetailTall":{"previewFrame":{"url":"x"},"video":"https://mvod.itunes.apple.com/a/tall.m3u8"},
            "motionDetailSquare":{"previewFrame":{"url":"https:\/\/is1.mzstatic.com\/p.png","width":2048},"video":"https:\/\/mvod.itunes.apple.com\/b\/square.m3u8"}}...
        """.trimIndent()
        assertEquals("https://mvod.itunes.apple.com/b/square.m3u8", AppleMotionArtworkRepository.squareMotionVideo(page))
        assertNull(AppleMotionArtworkRepository.squareMotionVideo("<html>no motion here</html>"))
    }

    @Test
    fun titlesAndArtistsAreCleanedForMatching() {
        assertEquals("Shake It Off", AppleMotionArtworkRepository.cleanTitle("Shake It Off (Official Video)"))
        assertEquals("Here Comes the Sun", AppleMotionArtworkRepository.cleanTitle("Here Comes the Sun - Remastered 2019"))
        assertEquals("Beyoncé", AppleMotionArtworkRepository.firstArtist("Beyoncé feat. JAY-Z"))
        assertEquals("beyonce", AppleMotionArtworkRepository.normalize("Beyoncé"))
    }
}
