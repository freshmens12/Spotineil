package com.spotineil.core.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.spotineil.core.common.AppDispatcher
import com.spotineil.core.common.Dispatcher
import com.spotineil.core.model.LocalTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Audio files on the device (MediaStore). Requires READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE. */
interface LocalMediaRepository {
    suspend fun tracks(): List<LocalTrack>

    /** Songs Spotineil downloaded (Music/Spotineil). */
    suspend fun downloads(): List<LocalTrack> = tracks(DOWNLOADS_SELECTION, arrayOf("%Music/Spotineil%"))

    suspend fun tracks(selection: String, args: Array<String>): List<LocalTrack> = emptyList()
}

@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(AppDispatcher.IO) private val io: CoroutineDispatcher,
) : LocalMediaRepository {

    override suspend fun tracks(): List<LocalTrack> = tracks("${MediaStore.Audio.Media.IS_MUSIC} != 0", emptyArray())

    override suspend fun tracks(selection: String, args: Array<String>): List<LocalTrack> = withContext(io) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val result = mutableListOf<LocalTrack>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args.takeIf { it.isNotEmpty() },
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val albumArt = ContentUris.withAppendedId(ALBUM_ART_URI, c.getLong(albumIdCol))
                    result += LocalTrack(
                        id = id,
                        contentUri = ContentUris.withAppendedId(collection, id).toString(),
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Unknown artist",
                        album = c.getString(albumCol) ?: "",
                        durationMs = c.getLong(durCol),
                        albumArtUri = albumArt.toString(),
                        sizeBytes = c.getLong(sizeCol),
                    )
                }
            }
        }
        result
    }

    private companion object {
        val ALBUM_ART_URI: android.net.Uri = android.net.Uri.parse("content://media/external/audio/albumart")
    }
}

/** MediaStore filter for files in Music/Spotineil (where downloads are saved). */
private val DOWNLOADS_SELECTION = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
