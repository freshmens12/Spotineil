package com.spotineil.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers where each song was left, so playing it again continues from there instead of the
 * start. Keeps the most recent [MAX_SONGS] songs; stored on the phone only.
 */
@Singleton
class SongPositionStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("song_positions", Context.MODE_PRIVATE)

    /** Where to continue [mediaId] (of [durationMs] long), or 0 to start from the beginning. */
    fun resumePoint(mediaId: String, durationMs: Long = 0L): Long {
        val ms = prefs.getLong(KEY + mediaId, 0L)
        if (ms < MIN_MS) return 0L
        // Left in the last seconds (or it finished): play it from the start next time.
        if (durationMs > 0 && ms > durationMs - END_MS) return 0L
        return ms
    }

    /** Remembers [positionMs] for [mediaId]. Positions in the first seconds are not worth keeping. */
    fun save(mediaId: String, positionMs: Long) {
        if (positionMs < MIN_MS) return
        val order = (prefs.getString(ORDER, "") ?: "").split(SEP).filter { it.isNotEmpty() && it != mediaId }.toMutableList()
        order += mediaId
        val edit = prefs.edit().putLong(KEY + mediaId, positionMs)
        while (order.size > MAX_SONGS) edit.remove(KEY + order.removeAt(0))
        edit.putString(ORDER, order.joinToString(SEP.toString())).apply()
    }

    private companion object {
        const val KEY = "pos:"
        const val ORDER = "order"
        const val SEP = '\n'
        const val MIN_MS = 3_000L
        const val END_MS = 10_000L
        const val MAX_SONGS = 200
    }
}
