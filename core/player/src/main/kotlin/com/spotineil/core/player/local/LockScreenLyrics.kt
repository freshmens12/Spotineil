package com.spotineil.core.player.local

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.spotineil.core.data.repository.LyricsQuery
import com.spotineil.core.data.repository.LyricsRepository
import com.spotineil.core.model.Lyrics
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The player as the media session shows it (notification, lock screen, Dynamic Island-style
 * pop-ups, car screens): the same player, except that while a lyric line is being sung the
 * song's details read "Title · Artist" with that line in place of the artist.
 *
 * Only the session-wide "now playing" details change. Each queued song keeps its own details,
 * which is what the app itself reads, so the app never shows a lyric line as an artist.
 */
@OptIn(UnstableApi::class)
internal class LyricsSessionPlayer(player: Player) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    /** The line shown now, or null to show the song as it is. */
    var line: String? = null
        private set

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }

    override fun getMediaMetadata(): MediaMetadata = withLyricLine(super.getMediaMetadata(), line)

    fun showLine(text: String?) {
        if (text == line) return
        line = text
        announce()
    }

    /** Tells the session (and so the notification and lock screen) that the details changed. */
    fun announce() {
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        for (listener in listeners) {
            listener.onMediaMetadataChanged(metadata)
            listener.onEvents(this, events)
        }
    }
}

/** [metadata] with [line] as the artist line, and the artist moved next to the title. */
internal fun withLyricLine(metadata: MediaMetadata, line: String?): MediaMetadata {
    if (line == null) return metadata
    val title = (metadata.displayTitle ?: metadata.title)?.toString()?.trim().orEmpty()
    val artist = metadata.artist?.toString()?.trim().orEmpty()
    val heading = if (artist.isEmpty() || title.isEmpty()) title.ifEmpty { artist } else "$title · $artist"
    return metadata.buildUpon()
        .setTitle(heading)
        .setDisplayTitle(heading)
        .setArtist(line)
        .setSubtitle(line)
        .build()
}

/**
 * Follows the song playing and shows its synced lyrics, line by line, through [sessionPlayer]
 * (Settings → Playback → Lyrics on the lock screen). Uses the same lyrics lookup (and cache) as
 * the lyrics screen. Songs without synced lyrics, radio, podcasts and previews show as usual.
 */
internal class LockScreenLyrics(
    private val player: Player,
    private val sessionPlayer: LyricsSessionPlayer,
    private val repository: LyricsRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reload()
        }

    private val main = Handler(Looper.getMainLooper())
    private var lyrics: Lyrics? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    fun start() {
        player.addListener(this)
        reload()
    }

    fun release() {
        player.removeListener(this)
        loadJob?.cancel()
        tickJob?.cancel()
        main.removeCallbacksAndMessages(null)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reload()

    override fun onIsPlayingChanged(isPlaying: Boolean) = follow()

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = follow()

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = follow()

    /** The player sent the song's own details again (e.g. tags read from the file): put the line back. */
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        if (sessionPlayer.line != null) main.post { if (sessionPlayer.line != null) sessionPlayer.announce() }
    }

    private fun reload() {
        loadJob?.cancel()
        tickJob?.cancel()
        lyrics = null
        sessionPlayer.showLine(null)
        if (!enabled) return
        val item = player.currentMediaItem ?: return
        val md = item.mediaMetadata
        val extras = md.extras
        val skip = extras?.getBoolean(EXTRA_IS_LIVE) == true ||
            extras?.getBoolean(EXTRA_IS_EPISODE) == true ||
            // A preview starts somewhere in the middle, so the timings wouldn't match.
            extras?.getBoolean(EXTRA_IS_PREVIEW) == true
        val title = md.title?.toString()?.trim().orEmpty()
        if (skip || title.isEmpty()) return
        // The same search the lyrics screen makes, so either one finds the other's answer saved.
        val artist = md.artist?.toString().orEmpty().substringBefore(',').trim()
        val album = md.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        val mediaId = item.mediaId
        loadJob = scope.launch {
            val durationMs = extras?.getLong(EXTRA_DURATION_MS)?.takeIf { it > 0 } ?: awaitDuration() ?: return@launch
            val found = runCatching { repository.lyrics(LyricsQuery(title, artist, album, durationMs)) }.getOrNull()
            ensureActive()
            if (player.currentMediaItem?.mediaId != mediaId) return@launch
            lyrics = found?.takeIf { it.isSynced && !it.isInstrumental && it.lines.isNotEmpty() } ?: return@launch
            follow()
        }
    }

    /** The song's length, once the player knows it (null if it never does, e.g. a stream). */
    private suspend fun awaitDuration(): Long? {
        repeat(DURATION_WAIT_STEPS) {
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0) return d
            delay(DURATION_WAIT_STEP_MS)
        }
        return null
    }

    /** Shows the line at the current position, then keeps up while the song plays. */
    private fun follow() {
        tickJob?.cancel()
        val lines = lyrics ?: return
        tickJob = scope.launch {
            while (true) {
                val position = player.currentPosition + LEAD_MS
                val index = lines.indexAt(position)
                sessionPlayer.showLine(index.takeIf { it >= 0 }?.let { lines.lines[it].text.trim().ifEmpty { INSTRUMENTAL } })
                if (!player.isPlaying) break
                val next = lines.lines.getOrNull(index + 1)?.timeMs ?: break
                val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
                delay(((next - position) / speed).toLong().coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
            }
        }
    }

    private companion object {
        /** Lock screens take a moment to redraw: change the line slightly early. */
        const val LEAD_MS = 150L
        const val MIN_WAIT_MS = 40L
        const val MAX_WAIT_MS = 1_000L
        const val DURATION_WAIT_STEPS = 40
        const val DURATION_WAIT_STEP_MS = 250L
        /** Shown between sung lines (an empty line in the lyrics). */
        const val INSTRUMENTAL = "♪"
    }
}
