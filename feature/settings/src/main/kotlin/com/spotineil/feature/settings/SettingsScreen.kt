package com.spotineil.feature.settings

import com.spotineil.core.designsystem.theme.dialogContainer
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
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
import com.spotineil.core.model.TransitionMode
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute
@Serializable data object LicensesRoute
@Serializable data object AboutRoute

fun NavController.navigateToSettings() = navigate(SettingsRoute)

fun NavGraphBuilder.settingsScreens(navController: NavController, onMessage: (String) -> Unit) {
    composable<SettingsRoute> {
        SettingsRoute(onBack = { navController.popBackStack() }, onAbout = { navController.navigate(AboutRoute) }, onMessage = onMessage)
    }
    composable<LicensesRoute> { LicensesScreen(onBack = { navController.popBackStack() }) }
    composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }, onLicenses = { navController.navigate(LicensesRoute) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val loggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val clearedMsg = stringResource(R.string.settings_cache_cleared)
    val context = LocalContext.current
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

            item { Header(stringResource(R.string.settings_playback)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_transition)) },
                    supportingContent = { Text(stringResource(settings.transition.description())) },
                    trailingContent = { Text(stringResource(settings.transition.label()), color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { dialog = "transition" },
                )
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
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_share)) },
                    supportingContent = { Text(stringResource(R.string.settings_share_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Share, null) },
                    modifier = Modifier.clickable { shareApp(context) },
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

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    supportingContent = { Text(stringResource(R.string.settings_about_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable(onClick = onAbout),
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
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Button(onClick = { openSupportPage(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_support))
                    }
                    Text(
                        stringResource(R.string.settings_support_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
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
        "transition" -> ChoiceDialog(
            title = stringResource(R.string.settings_transition),
            options = TransitionMode.entries.toList(),
            selected = settings.transition,
            label = { stringResource(it.label()) },
            hint = { stringResource(it.description()) },
            onSelect = { viewModel.setTransition(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "logout" -> AlertDialog(containerColor = dialogContainer(), 
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
    ThemeMode.Glass -> R.string.settings_theme_glass
}

private fun AppLanguage.label() = when (this) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.English -> R.string.settings_language_en
    AppLanguage.Spanish -> R.string.settings_language_es
    AppLanguage.Tagalog -> R.string.settings_language_fil
}

private fun TransitionMode.label() = when (this) {
    TransitionMode.Off -> R.string.settings_transition_off
    TransitionMode.Crossfade -> R.string.settings_transition_crossfade
    TransitionMode.AutoMix -> R.string.settings_transition_automix
}

private fun TransitionMode.description() = when (this) {
    TransitionMode.Off -> R.string.settings_transition_off_desc
    TransitionMode.Crossfade -> R.string.settings_transition_crossfade_desc
    TransitionMode.AutoMix -> R.string.settings_transition_automix_desc
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
    hint: (@Composable (T) -> String)? = null,
) {
    AlertDialog(containerColor = dialogContainer(), 
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
                        Column {
                            Text(label(option))
                            if (hint != null) {
                                Text(hint(option), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

/** One credit: what it is, what Spotineil uses it for, and under which license / terms. */
private class Credit(val name: String, val usedFor: String, val license: String)

private val libraryCredits = listOf(
    Credit("Spotineil", "This app: free software, you can share and change it", "GNU GPL v3.0 · the full text is in the LICENSE file of the source code"),
    Credit("InnerTube X (MetrolistGroup)", "Search, charts and streams of the music catalog", "GNU GPL v3.0 · github.com/MetrolistGroup/innertubex"),
    Credit("Jetpack Compose, Material 3, Navigation", "The app's screens and navigation", "Apache License 2.0"),
    Credit("AndroidX Core, AppCompat, Activity, Lifecycle, Splash Screen", "App foundations", "Apache License 2.0"),
    Credit("Media3 (ExoPlayer, MediaSession)", "Audio playback, notification and lock-screen controls", "Apache License 2.0"),
    Credit("Jetpack Glance", "Home screen widgets", "Apache License 2.0"),
    Credit("Room, Paging, DataStore", "On-device storage: history, likes and settings", "Apache License 2.0"),
    Credit("Palette", "Colors taken from the cover art", "Apache License 2.0"),
    Credit("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Language and background work", "Apache License 2.0"),
    Credit("Dagger / Hilt", "Dependency injection", "Apache License 2.0"),
    Credit("Ktor Client", "Network requests of the music catalog", "Apache License 2.0"),
    Credit("OkHttp, Retrofit", "Network requests", "Apache License 2.0"),
    Credit("Coil", "Loading images", "Apache License 2.0"),
    Credit("Gson", "Reading data", "Apache License 2.0"),
    Credit("Tink", "Encrypting data stored on the device", "Apache License 2.0"),
    Credit("AppAuth for Android", "Sign-in with Spotify (optional)", "Apache License 2.0"),
    Credit("Spotify Android SDK (App Remote)", "Controlling the Spotify app (optional)", "Spotify Developer Terms"),
    Credit("Reorderable (sh.calvin.reorderable)", "Drag to reorder the queue", "Apache License 2.0"),
    Credit("Manrope typeface", "The app's font", "SIL Open Font License 1.1"),
)

private val serviceCredits = listOf(
    Credit("YouTube Music", "Songs, artwork and charts", "Content belongs to its artists, labels and rights holders"),
    Credit("LRCLIB", "Song lyrics", "lrclib.net · community lyrics database"),
    Credit("Last.fm", "Similar artists for radio and Play similar", "Last.fm API Terms of Use"),
    Credit("Audius", "Independent artists' music", "Audius API terms"),
    Credit("Jamendo", "Free music by independent artists", "Creative Commons licenses chosen by each artist"),
    Credit("ccMixter", "Remixes and samples", "Creative Commons licenses chosen by each artist"),
    Credit("Internet Archive", "Live concerts and free albums", "Each item's own license, see archive.org"),
    Credit("Radio Browser", "Live radio stations", "radio-browser.info · community database"),
    Credit("Apple Podcasts directory (iTunes Search)", "Finding podcasts and their charts", "Apple Terms of Use. Episodes come from each show's own feed"),
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
            item { Header(stringResource(R.string.settings_licenses_libraries)) }
            items(libraryCredits, key = { it.name }) { CreditRow(it) }
            item { Header(stringResource(R.string.settings_licenses_services)) }
            items(serviceCredits, key = { it.name }) { CreditRow(it) }
            item {
                Text(
                    stringResource(R.string.settings_licenses_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CreditRow(credit: Credit) {
    Column {
        ListItem(
            headlineContent = { Text(credit.name) },
            supportingContent = {
                Column {
                    Text(credit.usedFor)
                    Text(credit.license, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        HorizontalDivider()
    }
}

private const val SUPPORT_URL = "https://vincentos.vercel.app/#/wallet"

/** Opens the developer's support page in the browser. */
private fun openSupportPage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing more to do.
    }
}

private const val SOURCE_URL = "https://github.com/freshmens12/Spotineil"
private const val APP_PAGE_URL = "https://github.com/freshmens12/Spotineil/releases/latest"

/** About: the version, a link to the source code, and the open-source licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomContentPadding.current + 24.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    supportingContent = { Text(stringResource(R.string.settings_licenses_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Description, null) },
                    modifier = Modifier.clickable(onClick = onLicenses),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_source_code)) },
                    supportingContent = { Text(stringResource(R.string.settings_source_code_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Code, null) },
                    modifier = Modifier.clickable { openUrl(context, SOURCE_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(viewModel.appVersion) },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing more to do.
    }
}

/** Opens Android's share sheet so the app's page can be sent through any app that accepts text. */
private fun shareApp(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_share_subject))
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settings_share_text, APP_PAGE_URL))
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can receive shared text.
    }
}
