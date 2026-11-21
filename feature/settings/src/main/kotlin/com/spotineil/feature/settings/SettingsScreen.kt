package com.spotineil.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.ui.platform.LocalContext
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.model.AccentColor
import com.spotineil.core.model.AppLanguage
import com.spotineil.core.model.ThemeMode
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute
@Serializable data object LicensesRoute

fun NavController.navigateToSettings() = navigate(SettingsRoute)

fun NavGraphBuilder.settingsScreens(navController: NavController, onMessage: (String) -> Unit) {
    composable<SettingsRoute> {
        SettingsRoute(onBack = { navController.popBackStack() }, onLicenses = { navController.navigate(LicensesRoute) }, onMessage = onMessage)
    }
    composable<LicensesRoute> { LicensesScreen(onBack = { navController.popBackStack() }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val loggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val clearedMsg = stringResource(R.string.settings_cache_cleared)
    val context = LocalContext.current
    val widgetFallbackMsg = stringResource(R.string.settings_widgets_fallback)
    LaunchedEffect(Unit) { viewModel.cacheCleared.collect { onMessage(clearedMsg) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 24.dp),
        ) {
            item { Header(stringResource(R.string.settings_appearance)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = { Text(stringResource(settings.themeMode.label())) },
                    modifier = Modifier.clickable { dialog = "theme" },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyLarge)
                    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AccentColor.entries.toList(), key = { it.name }) { accent ->
                            val selected = accent == settings.accent
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(accent.argb))
                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { viewModel.setAccent(accent) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Rounded.Check, accent.name, tint = Color.Black)
                            }
                        }
                    }
                }
            }

            item { Header(stringResource(R.string.settings_data)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_data_saver)) },
                    supportingContent = { Text(stringResource(R.string.settings_data_saver_desc)) },
                    trailingContent = { Switch(settings.dataSaver, viewModel::setDataSaver) },
                    modifier = Modifier.clickable { viewModel.setDataSaver(!settings.dataSaver) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_cache_desc)) },
                    modifier = Modifier.clickable { viewModel.clearCache() },
                )
            }

            item { Header(stringResource(R.string.settings_general)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = { Text(stringResource(settings.language.label())) },
                    modifier = Modifier.clickable { dialog = "language" },
                )
            }
            if (loggedIn) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { dialog = "logout" },
                    )
                }
            }

            item { Header(stringResource(R.string.settings_widgets)) }
            item {
                Text(
                    stringResource(R.string.settings_widgets_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(HomeWidgets, key = { it.receiver }) { widget ->
                ListItem(
                    headlineContent = { Text(stringResource(widget.label)) },
                    supportingContent = { Text(stringResource(widget.description)) },
                    trailingContent = { Icon(Icons.Rounded.Add, null) },
                    modifier = Modifier.clickable { if (!pinWidget(context, widget.receiver)) onMessage(widgetFallbackMsg) },
                )
            }

            item { Header(stringResource(R.string.settings_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(viewModel.appVersion) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    modifier = Modifier.clickable(onClick = onLicenses),
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.toList(),
            selected = settings.themeMode,
            label = { stringResource(it.label()) },
            onSelect = { viewModel.setTheme(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "language" -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries.toList(),
            selected = settings.language,
            label = { stringResource(it.label()) },
            onSelect = { viewModel.setLanguage(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "logout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = { TextButton(onClick = { dialog = null; viewModel.logout() }) { Text(stringResource(R.string.settings_logout)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
    ThemeMode.Amoled -> R.string.settings_theme_amoled
}

private fun AppLanguage.label() = when (this) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.English -> R.string.settings_language_en
    AppLanguage.Spanish -> R.string.settings_language_es
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onSelect(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

private val licenses = listOf(
    "AndroidX, Jetpack Compose, Media3, Room, Paging, DataStore, Glance" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "Dagger / Hilt" to "Apache License 2.0",
    "Retrofit, OkHttp" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Tink" to "Apache License 2.0",
    "AppAuth for Android" to "Apache License 2.0",
    "Spotify Android SDK (App Remote)" to "Apache License 2.0 (see Spotify Developer Terms)",
    "Reorderable (sh.calvin.reorderable)" to "Apache License 2.0",
    "Manrope typeface" to "SIL Open Font License 1.1",
    "Audius API, Jamendo API, Radio Browser, Deezer API, iTunes Search API" to "Used under each service's public API terms",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 24.dp),
        ) {
            items(licenses, key = { it.first }) { (name, license) ->
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text(license) })
                HorizontalDivider()
            }
        }
    }
}

/** The widgets of the app; [receiver] is the class name in the app module (no code dependency needed). */
private class HomeWidget(val receiver: String, val label: Int, val description: Int)

private val HomeWidgets = listOf(
    HomeWidget("PlayerWidgetReceiver", R.string.settings_widget_player, R.string.settings_widget_player_desc),
    HomeWidget("NowPlayingWidgetReceiver", R.string.settings_widget_now_playing, R.string.settings_widget_now_playing_desc),
    HomeWidget("MiniWidgetReceiver", R.string.settings_widget_mini, R.string.settings_widget_mini_desc),
    HomeWidget("ShortcutsWidgetReceiver", R.string.settings_widget_shortcuts, R.string.settings_widget_shortcuts_desc),
    HomeWidget("RecentWidgetReceiver", R.string.settings_widget_recent, R.string.settings_widget_recent_desc),
)

/** Asks the launcher to place the widget on the Home screen (the user confirms). False if the launcher can't. */
private fun pinWidget(context: Context, receiver: String): Boolean {
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported) return false
    return manager.requestPinAppWidget(ComponentName(context.packageName, "com.spotineil.app.widget.$receiver"), null, null)
}
