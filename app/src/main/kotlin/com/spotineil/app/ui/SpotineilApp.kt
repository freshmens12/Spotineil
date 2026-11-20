package com.spotineil.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.spotineil.app.MainViewModel
import com.spotineil.app.R
import com.spotineil.app.auth.openSpotifyInPlayStore
import com.spotineil.core.designsystem.theme.LocalBottomContentPadding
import com.spotineil.core.designsystem.theme.LocalPipState
import com.spotineil.core.player.PlayerEvent
import com.spotineil.feature.album.albumScreen
import com.spotineil.feature.album.navigateToAlbum
import com.spotineil.feature.artist.artistScreen
import com.spotineil.feature.artist.navigateToArtist
import com.spotineil.feature.home.HomeRoute
import com.spotineil.feature.home.homeScreen
import com.spotineil.feature.library.LibraryCallbacks
import com.spotineil.feature.discover.DiscoverRoute
import com.spotineil.feature.discover.discoverScreen
import com.spotineil.feature.discover.free.FreeHomeTab
import com.spotineil.feature.discover.free.FreeLibraryTab
import com.spotineil.feature.discover.free.FreeNavigation
import com.spotineil.feature.discover.free.FreeSearchTab
import com.spotineil.feature.discover.free.LikedMediaRoute
import com.spotineil.feature.discover.free.YouTubePlayerRoute
import com.spotineil.feature.discover.free.HistoryRoute
import com.spotineil.feature.discover.free.freeScreens
import com.spotineil.feature.discover.free.toRoute
import androidx.compose.ui.graphics.Brush
import com.spotineil.feature.library.LibraryRoute
import com.spotineil.feature.library.LocalMusicTabRoute
import com.spotineil.feature.library.localMusicTab
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Explore
import com.spotineil.feature.library.libraryScreens
import com.spotineil.feature.library.navigateToLikedSongs
import com.spotineil.feature.library.navigateToLocalFiles
import com.spotineil.feature.player.PlayerHost
import com.spotineil.feature.playlist.navigateToPlaylist
import com.spotineil.feature.playlist.playlistScreen
import com.spotineil.feature.profile.navigateToProfile
import com.spotineil.feature.profile.profileScreen
import com.spotineil.feature.search.SearchCallbacks
import com.spotineil.feature.search.SearchRoute
import com.spotineil.feature.search.searchScreen
import com.spotineil.feature.settings.navigateToSettings
import com.spotineil.feature.settings.settingsScreens
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val discoverTab = TopLevel(DiscoverRoute, DiscoverRoute::class, R.string.nav_discover, Icons.Outlined.Explore, Icons.Rounded.Explore)

/** With a Spotify (Premium developer) login. */
private val spotifyTabs = listOf(
    TopLevel(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
    TopLevel(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Outlined.Search, Icons.Rounded.Search),
    discoverTab,
    TopLevel(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/** The default app: free music, radio, podcasts and files on the phone — no account. */
private val freeTabs = listOf(
    TopLevel(FreeHomeTab, FreeHomeTab::class, R.string.nav_home, Icons.Outlined.Home, Icons.Rounded.Home),
    TopLevel(FreeSearchTab, FreeSearchTab::class, R.string.nav_search, Icons.Outlined.Search, Icons.Rounded.Search),
    TopLevel(FreeLibraryTab, FreeLibraryTab::class, R.string.nav_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/** Bottom bar height above the system navigation insets. */
private val NavBarHeight = 64.dp
private val MiniPlayerHeight = 64.dp

private enum class AppMode { Welcome, Spotify, Free }

@Composable
fun SpotineilApp(isLoggedIn: Boolean, guestMode: Boolean, mainViewModel: MainViewModel) {
    // First launch shows the Welcome screen once; after "Get started" the app opens straight into
    // the free app. (The Spotify mode is only reachable by an existing Spotify login.)
    val mode = when {
        isLoggedIn -> AppMode.Spotify
        guestMode -> AppMode.Free
        else -> AppMode.Welcome
    }
    AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "auth") { m ->
        when (m) {
            AppMode.Spotify -> MainShell(mainViewModel, free = false)
            AppMode.Free -> MainShell(mainViewModel, free = true)
            AppMode.Welcome -> WelcomeScreen(onGetStarted = { mainViewModel.finishWelcome() })
        }
    }
    UpdateDialog()
}

@Composable
private fun MainShell(mainViewModel: MainViewModel, free: Boolean) {
    val tabs = if (free) freeTabs else spotifyTabs
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasTrack by mainViewModel.hasTrack.collectAsStateWithLifecycle()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    // Media notification for local playback (Android 13+).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val msgPremium = stringResource(R.string.event_premium_required)
    val msgNoDevice = stringResource(R.string.event_no_device)
    val msgNotInstalled = stringResource(R.string.event_spotify_missing)
    val msgNotLoggedIn = stringResource(R.string.event_spotify_logged_out)
    val msgGeneric = stringResource(R.string.event_error)
    val actionGet = stringResource(R.string.event_get_spotify)
    LaunchedEffect(Unit) {
        mainViewModel.playerEvents.collect { event ->
            when (event) {
                PlayerEvent.PremiumRequired -> snackbar.showSnackbar(msgPremium)
                PlayerEvent.NoActiveDevice -> snackbar.showSnackbar(msgNoDevice)
                PlayerEvent.SpotifyNotLoggedIn -> snackbar.showSnackbar(msgNotLoggedIn)
                PlayerEvent.SpotifyNotInstalled -> {
                    if (snackbar.showSnackbar(msgNotInstalled, actionGet) == SnackbarResult.ActionPerformed) {
                        openSpotifyInPlayStore(context)
                    }
                }
                is PlayerEvent.Error -> snackbar.showSnackbar(event.message ?: msgGeneric)
            }
        }
    }

    val navInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The full player screen already shows the song, so the mini player is hidden there.
    val currentEntry by navController.currentBackStackEntryAsState()
    val onFullPlayer = currentEntry?.destination?.hasRoute(YouTubePlayerRoute::class) == true
    val showMini = hasTrack && !onFullPlayer
    val bottomPadding = NavBarHeight + navInsets + if (showMini) MiniPlayerHeight else 0.dp

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomPadding)) },
            contentWindowInsets = WindowInsets(0),
        ) { _ ->
            CompositionLocalProvider(LocalBottomContentPadding provides bottomPadding) {
                AppNavHost(navController, showMessage, startDestination = tabs.first().route)
            }
        }
        // In the floating PiP window only the YouTube video is shown: no bar, mini player or sheets.
        if (!LocalPipState.current.isInPip) {
            BottomBar(navController, tabs, Modifier.align(Alignment.BottomCenter))
            if (!onFullPlayer || playerExpanded) PlayerHost(
                expanded = playerExpanded,
                onExpandedChange = { playerExpanded = it },
                onArtistClick = { navController.navigateToArtist(it) },
                onAlbumClick = { navController.navigateToAlbum(it) },
                miniPlayerModifier = Modifier.navigationBarsPadding().padding(bottom = NavBarHeight + 2.dp),
            )
        }
    }
}

/** Home / Search / Library over a fade-to-black gradient, so content scrolls softly underneath. */
@Composable
private fun BottomBar(navController: NavHostController, tabs: List<TopLevel>, modifier: Modifier = Modifier) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val background = MaterialTheme.colorScheme.background
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background.copy(alpha = 0.92f), background)))
            .navigationBarsPadding()
            .height(NavBarHeight)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabColor",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (selected) item.selectedIcon else item.icon, null, tint = color, modifier = Modifier.size(26.dp))
                Text(
                    stringResource(item.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, onMessage: (String) -> Unit, startDestination: Any) {
    val back: () -> Unit = { navController.popBackStack() }
    val toArtist: (String) -> Unit = { navController.navigateToArtist(it) }
    val toAlbum: (String) -> Unit = { navController.navigateToAlbum(it) }
    val toPlaylist: (String) -> Unit = { navController.navigateToPlaylist(it) }
    val freeNav = remember(navController, onMessage) {
        FreeNavigation(
            onPodcast = { navController.navigate(it.toRoute()) },
            onCollection = { navController.navigate(it.toRoute()) },
            onBrowse = { navController.navigate(it) },
            onLikedSongs = { navController.navigate(LikedMediaRoute) },
            onHistory = { navController.navigate(HistoryRoute) },
            onYouTubePlayer = { navController.navigate(YouTubePlayerRoute) { launchSingleTop = true } },
            onLocalMusic = { navController.navigateToLocalFiles() },
            onSettings = { navController.navigateToSettings() },
            onBack = back,
            onMessage = onMessage,
        )
    }

    NavHost(navController, startDestination = startDestination, modifier = Modifier.fillMaxSize()) {
        freeScreens(freeNav)
        discoverScreen(onSettingsClick = { navController.navigateToSettings() }, onMessage = onMessage)
        localMusicTab()
        homeScreen(
            onArtistClick = toArtist,
            onAlbumClick = toAlbum,
            onPlaylistClick = toPlaylist,
            onLikedSongsClick = { navController.navigateToLikedSongs() },
            onProfileClick = { navController.navigateToProfile() },
            onSettingsClick = { navController.navigateToSettings() },
        )
        searchScreen(SearchCallbacks(toArtist, toAlbum, toPlaylist, onMessage))
        libraryScreens(
            LibraryCallbacks(
                onPlaylistClick = toPlaylist,
                onAlbumClick = toAlbum,
                onArtistClick = toArtist,
                onLikedSongsClick = { navController.navigateToLikedSongs() },
                onLocalFilesClick = { navController.navigateToLocalFiles() },
                onMessage = onMessage,
            ),
            onBack = back,
        )
        albumScreen(back, toArtist, toAlbum, onMessage)
        artistScreen(back, toArtist, toAlbum, onMessage)
        playlistScreen(back, toArtist, toAlbum, onOwnerClick = { navController.navigateToProfile(it) }, onMessage)
        profileScreen(back, toArtist)
        settingsScreens(navController, onMessage)
    }
}
