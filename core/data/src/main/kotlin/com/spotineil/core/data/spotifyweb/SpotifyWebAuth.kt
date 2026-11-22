package com.spotineil.core.data.spotifyweb

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects Spotineil to the person's Spotify account through Spotify's own web player.
 *
 * The person signs in to open.spotify.com in a WebView (their password never reaches the app).
 * The web player then asks Spotify for a short-lived access token with the session cookie
 * (`sp_dc`), which stays inside the WebView's own cookie storage. The app only reads the token
 * the web player sends, and uses it to read playlists and Liked Songs. When the token runs out,
 * a hidden WebView opens the web player again with the saved session to get a new one.
 *
 * This is Spotify's unofficial web-player route, not its public API: it can stop working when
 * Spotify changes its site, and Spotify's terms do not allow it for apps. Personal use only.
 */
@Singleton
class SpotifyWebAuth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("spotify_web", Context.MODE_PRIVATE)

    private val _connected = MutableStateFlow(prefs.getBoolean(CONNECTED, false))
    /** True after a successful sign-in, until [disconnect]. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _accountName = MutableStateFlow(prefs.getString(ACCOUNT, null))
    /** The Spotify display name of the connected account. */
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    private val refreshLock = Mutex()

    /** Called by a web player WebView whenever it sees a request carrying an access token. */
    fun onTokenSeen(bearer: String) {
        val value = bearer.removePrefix("Bearer ").trim()
        if (value.length < MIN_TOKEN_LENGTH) return
        token = value
        tokenAt = System.currentTimeMillis()
        CookieManager.getInstance().flush()
    }

    private fun fresh(): String? = token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_LIFETIME_MS }

    /**
     * An access token for Spotify's API, or null when the session can no longer be used (the
     * person has to connect again). [forceRefresh] ignores the saved token (after a 401).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        if (!_connected.value) return null
        return anyToken(forceRefresh)
    }

    /** Like [accessToken], but also while connecting (before the account was confirmed). */
    suspend fun anyToken(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) fresh()?.let { return it }
        return refreshLock.withLock {
            if (!forceRefresh) fresh()?.let { return it }
            withContext(Dispatchers.Main) { fetchWithHiddenWebView() }
        }
    }

    /** The newest token the web player showed, if it is still fresh (used while signing in). */
    fun latestToken(): String? = fresh()

    /**
     * Uses the person's own `sp_dc` cookie (copied from Spotify in a desktop browser): it is put in
     * the app's web storage, then the hidden web player gets a token with it. Returns that token,
     * or null if the web player didn't produce one. The caller checks it belongs to an account.
     */
    suspend fun tokenFromCookie(spDc: String): String? {
        val value = spDc.trim().removePrefix("sp_dc=").substringBefore(';').trim()
        if (value.isEmpty()) return null
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setCookie("https://open.spotify.com", "sp_dc=$value; Domain=.spotify.com; Path=/; Secure; HttpOnly")
            cookies.flush()
        }
        token = null
        tokenAt = 0L
        return anyToken(forceRefresh = true)
    }

    /** Remembers that the sign-in worked and whose account it is. */
    fun markConnected(accountName: String?) {
        prefs.edit().putBoolean(CONNECTED, true).putString(ACCOUNT, accountName).apply()
        _accountName.value = accountName
        _connected.value = true
    }

    /** Opens the web player invisibly and waits for it to ask Spotify for a token. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithHiddenWebView(): String? {
        val started = System.currentTimeMillis()
        val view = newWebView(context)
        try {
            view.loadUrl(WEB_PLAYER_URL)
            return withTimeoutOrNull(HIDDEN_TIMEOUT_MS) {
                while (tokenAt < started || token == null) kotlinx.coroutines.delay(250)
                token
            }
        } finally {
            Handler(Looper.getMainLooper()).post { view.stopLoading(); view.destroy() }
        }
    }

    /** A WebView that reports the web player's access tokens to [onTokenSeen]. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    fun newWebView(viewContext: Context): WebView {
        val webView = WebView(viewContext)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The phone's own Chrome identity, minus the "embedded WebView" marks. A desktop identity
            // contradicts what the browser reports about itself, and Spotify's sign-in then refuses.
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(viewContext)
                .replace("; wv)", ")")
                .replace(Regex("Version/[0-9.]+ "), "")
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host.orEmpty()
                if (host.endsWith("spotify.com")) {
                    request.requestHeaders.entries
                        .firstOrNull { it.key.equals("Authorization", ignoreCase = true) && it.value.startsWith("Bearer ") }
                        ?.let { onTokenSeen(it.value) }
                }
                return null // let the page load as usual
            }
        }
        return webView
    }

    /** Signs out: forgets the token and removes Spotify's cookies from this app's web storage. */
    fun disconnect() {
        token = null
        tokenAt = 0L
        prefs.edit().putBoolean(CONNECTED, false).remove(ACCOUNT).apply()
        _accountName.value = null
        _connected.value = false
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    companion object {
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private const val WEB_PLAYER_URL = "https://open.spotify.com/"
        private const val CONNECTED = "connected"
        private const val ACCOUNT = "account"
        private const val MIN_TOKEN_LENGTH = 60
        // Web-player tokens last an hour; renew a little early.
        private const val TOKEN_LIFETIME_MS = 50 * 60_000L
        private const val HIDDEN_TIMEOUT_MS = 30_000L
    }
}
