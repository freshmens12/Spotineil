package com.spotineil.core.data.lyrics

import com.spotineil.core.model.LyricLine
import com.spotineil.core.model.Lyrics

/**
 * Parser for the LRC format used by LRCLIB:
 *  - `[mm:ss.xx]text`, `[mm:ss.xxx]text`, `[mm:ss]text`, `[mm:ss:xx]text`
 *  - several timestamps on one line (`[00:10.00][00:42.50]chorus`)
 *  - metadata tags (`[ar:...]`, `[ti:...]`) are ignored, except `[offset:±ms]`
 *  - word-level `<mm:ss.xx>` markers (enhanced LRC) are stripped
 */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): List<LyricLine> {
        var offsetMs = 0L
        val result = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            offsetTag.find(line)?.let {
                offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val match = timeTag.matchAt(rest, 0) ?: break
                times += match.toMillis()
                rest = rest.substring(match.range.last + 1)
            }
            if (times.isEmpty()) return@forEach
            val text = rest.replace(wordTag, "").trim()
            times.forEach { result += LyricLine(timeMs = it, text = text) }
        }
        // Positive offset means lyrics should appear sooner.
        return result
            .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0)) }
            .sortedBy { it.timeMs }
    }

    fun toLyrics(synced: String?, plain: String?, instrumental: Boolean): Lyrics? {
        if (instrumental) return Lyrics(lines = emptyList(), isSynced = false, isInstrumental = true)
        if (!synced.isNullOrBlank()) {
            val lines = parse(synced)
            if (lines.isNotEmpty()) return Lyrics(lines = lines, isSynced = true)
        }
        if (!plain.isNullOrBlank()) {
            return Lyrics(
                lines = plain.lines().map { LyricLine(timeMs = 0, text = it.trim()) },
                isSynced = false,
            )
        }
        return null
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }
}
