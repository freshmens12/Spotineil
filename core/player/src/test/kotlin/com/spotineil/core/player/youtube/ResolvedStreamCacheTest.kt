package com.spotineil.core.player.youtube

import com.spotineil.core.data.innertube.ResolvedYouTubeStream
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedStreamCacheTest {

    private fun stream(id: String, expiresAt: Long?) =
        ResolvedYouTubeStream(id, "https://example.invalid/$id", emptyMap(), expiresAt, null) { null }

    @After
    fun clear() = ResolvedStreamCache.clear()

    @Test
    fun aRecentLookupIsReusedUntilItGetsOld() {
        val t0 = 1_000_000_000L
        ResolvedStreamCache.put(stream("a", expiresAt = null), now = t0)
        assertNotNull(ResolvedStreamCache.get("a", now = t0 + 60_000))
        assertNull(ResolvedStreamCache.get("a", now = t0 + 16 * 60_000))
    }

    @Test
    fun aLinkAboutToExpireIsLookedUpAgain() {
        val t0 = 1_000_000_000L
        ResolvedStreamCache.put(stream("b", expiresAt = t0 + 90_000), now = t0)
        assertNull(ResolvedStreamCache.get("b", now = t0))
        ResolvedStreamCache.put(stream("c", expiresAt = t0 + 6 * 3_600_000), now = t0)
        assertNotNull(ResolvedStreamCache.get("c", now = t0 + 5 * 60_000))
        assertNull(ResolvedStreamCache.get("unknown", now = t0))
    }
}
