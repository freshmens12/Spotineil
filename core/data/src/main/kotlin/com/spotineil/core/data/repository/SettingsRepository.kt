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
        )
    }

    override suspend fun setGuestMode(enabled: Boolean) { store.edit { it[GUEST] = enabled } }

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
    }
}
