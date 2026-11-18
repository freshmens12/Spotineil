package com.spotineil.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.app.R
import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.designsystem.component.errorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

private const val SPOTIFY_PACKAGE = "com.spotify.music"

data class LoginUiState(
    val isConfigured: Boolean = true,
    val spotifyInstalled: Boolean = true,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val cancelled: Boolean = false,
)

/**
 * Authorization Code with PKCE. The user signs in on Spotify's own page in a Custom Tab;
 * Spotineil never sees a password. AppAuth generates the S256 code verifier/challenge.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val config: SpotifyAppConfig,
    private val settings: com.spotineil.core.data.repository.SettingsRepository,
) : ViewModel() {

    /** Free sources, radio and local music only — no Spotify account needed. */
    fun continueAsGuest() = viewModelScope.launch { settings.setGuestMode(true) }

    private val _state = MutableStateFlow(LoginUiState(isConfigured = config.isConfigured))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init { refreshSpotifyInstalled() }

    fun refreshSpotifyInstalled() {
        val installed = runCatching { context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0); true }
            .getOrElse { it !is PackageManager.NameNotFoundException }
        _state.update { it.copy(spotifyInstalled = installed) }
    }

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.Builder(
        AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.spotify.com/authorize"),
            Uri.parse("https://accounts.spotify.com/api/token"),
        ),
        config.clientId,
        ResponseTypeValues.CODE,
        Uri.parse(config.redirectUri),
    ).setScopes(config.scopes).build()

    fun onAuthStarted() = _state.update { it.copy(isLoading = true, error = null, cancelled = false) }

    fun onAuthResult(data: Intent?) {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val exception = data?.let { AuthorizationException.fromIntent(it) }
        val code = response?.authorizationCode
        val verifier = response?.request?.codeVerifier
        if (code == null || verifier == null) {
            _state.update { it.copy(isLoading = false, cancelled = exception == null, error = exception) }
            return
        }
        viewModelScope.launch {
            runCatching { auth.completeLogin(code, verifier) }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e) } }
            // On success the token store emits and the app switches to the main UI.
        }
    }

    fun onLaunchFailed(e: Throwable) = _state.update { it.copy(isLoading = false, error = e) }
}

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val authService = remember { AuthorizationService(context) }
    DisposableEffect(Unit) { onDispose { authService.dispose() } }

    // Re-check whether Spotify got installed when we come back from the Play Store.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) viewModel.refreshSpotifyInstalled() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onAuthResult(result.data)
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.background)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_spotineil_mark), null, Modifier.size(112.dp))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.login_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))

            if (!state.isConfigured) {
                Notice(stringResource(R.string.login_not_configured))
            } else {
                Button(
                    onClick = {
                        viewModel.onAuthStarted()
                        try {
                            launcher.launch(authService.getAuthorizationRequestIntent(viewModel.authorizationRequest()))
                        } catch (e: ActivityNotFoundException) {
                            viewModel.onLaunchFailed(e)
                        }
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.login_button), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = viewModel::continueAsGuest, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.login_guest), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                stringResource(R.string.login_guest_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(errorMessage(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (state.cancelled) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.login_cancelled), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!state.spotifyInstalled) {
                Spacer(Modifier.height(24.dp))
                Notice(stringResource(R.string.login_spotify_missing))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openSpotifyInPlayStore(context) }) { Text(stringResource(R.string.login_get_spotify)) }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.login_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun openSpotifyInPlayStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
