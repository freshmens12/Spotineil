package com.spotineil.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spotineil.core.model.AccentColor
import com.spotineil.core.model.AppLanguage
import com.spotineil.core.model.LibrarySort
import com.spotineil.core.model.ThemeMode
import com.spotineil.core.model.TransitionMode
import com.spotineil.core.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAccent(accent: AccentColor)
    suspend fun setDataSaver(enabled: Boolean)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setLibrarySort(sort: LibrarySort)
    suspend fun setLibraryGrid(grid: Boolean)
    suspend fun setGuestMode(enabled: Boolean)
    suspend fun setTransition(mode: TransitionMode)
    suspend fun setAudio(audio: com.spotineil.core.model.AudioSettings)
    suspend fun setDownloads(downloads: com.spotineil.core.model.DownloadSettings)
    suspend fun setCoverStyle(style: com.spotineil.core.model.CoverStyle)
    suspend fun setAnimatedArtwork(enabled: Boolean)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val store = context.settingsDataStore

    override val settings: Flow<UserSettings> = store.data.map { p ->
        val defaults = UserSettings()
        UserSettings(
            themeMode = p[THEME].toEnum(defaults.themeMode),
            accent = p[ACCENT].toEnum(defaults.accent),
            dataSaver = p[DATA_SAVER] ?: defaults.dataSaver,
            language = p[LANGUAGE].toEnum(defaults.language),
            librarySort = p[LIB_SORT].toEnum(defaults.librarySort),
            libraryGrid = p[LIB_GRID] ?: defaults.libraryGrid,
            guestMode = p[GUEST] ?: defaults.guestMode,
            transition = p[TRANSITION].toEnum(defaults.transition),
            audio = com.spotineil.core.model.AudioSettings(
                eqEnabled = p[EQ_ON] ?: false,
                eqPreset = p[EQ_PRESET].toEnum(com.spotineil.core.model.EqPreset.Flat),
                eqCustom = p[EQ_CUSTOM]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 5 } ?: listOf(0, 0, 0, 0, 0),
                volumeLeveling = p[LEVELING] ?: false,
                skipSilence = p[SKIP_SILENCE] ?: false,
                podcastSpeed = p[PODCAST_SPEED]?.toFloatOrNull() ?: 1f,
            ),
            // Before there were styles, the animation could only be switched off.
            coverStyle = p[COVER_STYLE].toEnum(
                if (p[ANIMATED_COVER] == false) com.spotineil.core.model.CoverStyle.Still else defaults.coverStyle,
            ),
            animatedArtwork = p[ANIMATED_ARTWORK] ?: defaults.animatedArtwork,
            downloads = com.spotineil.core.model.DownloadSettings(
                folderUri = p[DL_FOLDER_URI],
                folderName = p[DL_FOLDER_NAME],
                format = p[DL_FORMAT].toEnum(defaults.downloads.format),
                maxParallel = (p[DL_PARALLEL] ?: defaults.downloads.maxParallel).coerceIn(1, com.spotineil.core.model.DownloadSettings.MAX_PARALLEL),
            ),
        )
    }

    override suspend fun setGuestMode(enabled: Boolean) { store.edit { it[GUEST] = enabled } }
    override suspend fun setTransition(mode: TransitionMode) { store.edit { it[TRANSITION] = mode.name } }
    override suspend fun setAudio(audio: com.spotineil.core.model.AudioSettings) {
        store.edit {
            it[EQ_ON] = audio.eqEnabled
            it[EQ_PRESET] = audio.eqPreset.name
            it[EQ_CUSTOM] = audio.eqCustom.joinToString(",")
            it[LEVELING] = audio.volumeLeveling
            it[SKIP_SILENCE] = audio.skipSilence
            it[PODCAST_SPEED] = audio.podcastSpeed.toString()
        }
    }

    override suspend fun setAnimatedArtwork(enabled: Boolean) { store.edit { it[ANIMATED_ARTWORK] = enabled } }

    override suspend fun setCoverStyle(style: com.spotineil.core.model.CoverStyle) { store.edit { it[COVER_STYLE] = style.name } }

    override suspend fun setDownloads(downloads: com.spotineil.core.model.DownloadSettings) {
        store.edit {
            it[DL_FORMAT] = downloads.format.name
            it[DL_PARALLEL] = downloads.maxParallel.coerceIn(1, com.spotineil.core.model.DownloadSettings.MAX_PARALLEL)
            downloads.folderUri?.let { uri -> it[DL_FOLDER_URI] = uri } ?: it.remove(DL_FOLDER_URI)
            downloads.folderName?.let { name -> it[DL_FOLDER_NAME] = name } ?: it.remove(DL_FOLDER_NAME)
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[THEME] = mode.name } }
    override suspend fun setAccent(accent: AccentColor) { store.edit { it[ACCENT] = accent.name } }
    override suspend fun setDataSaver(enabled: Boolean) { store.edit { it[DATA_SAVER] = enabled } }
    override suspend fun setLanguage(language: AppLanguage) { store.edit { it[LANGUAGE] = language.name } }
    override suspend fun setLibrarySort(sort: LibrarySort) { store.edit { it[LIB_SORT] = sort.name } }
    override suspend fun setLibraryGrid(grid: Boolean) { store.edit { it[LIB_GRID] = grid } }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val LANGUAGE = stringPreferencesKey("language")
        val LIB_SORT = stringPreferencesKey("library_sort")
        val LIB_GRID = booleanPreferencesKey("library_grid")
        val GUEST = booleanPreferencesKey("guest_mode")
        val TRANSITION = stringPreferencesKey("transition")
        val EQ_ON = booleanPreferencesKey("eq_on")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_CUSTOM = stringPreferencesKey("eq_custom")
        val LEVELING = booleanPreferencesKey("volume_leveling")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val PODCAST_SPEED = stringPreferencesKey("podcast_speed")
        val DL_FORMAT = stringPreferencesKey("download_format")
        val ANIMATED_COVER = booleanPreferencesKey("animated_cover")
        val COVER_STYLE = stringPreferencesKey("cover_style")
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val DL_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        val DL_FOLDER_NAME = stringPreferencesKey("download_folder_name")
        val DL_PARALLEL = androidx.datastore.preferences.core.intPreferencesKey("download_parallel")
    }
}
