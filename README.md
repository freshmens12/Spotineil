# Spotineil

An unofficial Spotify client for Android, written in Kotlin with Jetpack Compose (Material 3).

Spotineil uses **only official, ToS-compliant Spotify integrations**:

- [Spotify Web API](https://developer.spotify.com/documentation/web-api)
- [Spotify App Remote SDK](https://developer.spotify.com/documentation/android)
- Authorization Code + PKCE login

It does **not** block ads, download or rip audio from Spotify or any other service, or work around Spotify Free limits. You sign in on Spotify's own page, and Spotineil never sees your password.

> **Disclaimer:** Spotineil is an independent, unofficial project. It is **not affiliated with, endorsed, or sponsored by Spotify AB**. "Spotify" is a trademark of Spotify AB. All Spotify content, metadata, and artwork are provided by Spotify. Lyrics are provided by [LRCLIB](https://lrclib.net).

---

## Features

| Area | What you get |
|---|---|
| **Login** | Branded splash screen and "Log in with Spotify" (PKCE in a Custom Tab). Detects whether the Spotify app is installed and links to Google Play if it isn't. |
| **Home** | Time-of-day greeting, quick picks, recently played, top artists and tracks, your playlists, new releases, saved albums. Pull-to-refresh, skeleton loaders, offline cache. |
| **Search** | Debounced search across songs, artists, albums, playlists and podcasts. Filter chips with fully paginated results, recent searches, and a browse-categories grid. |
| **Library** | Playlists, albums, artists and podcasts tabs. Sort by recent, A–Z or creator. Grid/list toggle, Liked Songs, local files. |
| **Detail pages** | Album, playlist, artist and profile pages with a collapsing header tinted by the artwork's color (Palette API), and play/shuffle buttons. |
| **Track menu** | Add to queue, add to playlist, like/unlike, go to artist, go to album, share. |
| **Player** | Mini player above the bottom nav expands into a full player with a shared-element transition. Blurred artwork background, seek bar, shuffle/repeat, like, queue sheet, Spotify Connect device picker, sleep timer. |
| **Lyrics** | Synced line-by-line lyrics from LRCLIB, matched by track, artist, album and duration. Auto-scroll, tap a line to seek, and a "Lyrics not available" state. |
| **Playlist management** | Create, rename, edit description, toggle public/collaborative, follow/unfollow, remove tracks, drag to reorder. |
| **Stats** | Your top tracks and artists over 4 weeks, 6 months and all time. |
| **Settings** | Theme (System / Light / Dark / AMOLED black), accent color, data saver, language (English / Español), clear cache, log out, About (version, licenses, attribution). |
| **Extras** | Home-screen widget (Glance) with now playing and controls. Local-file playback with notification, lock-screen and Bluetooth controls, plus Android Auto browsing. |
| **YouTube (main catalog)** | With a `YOUTUBE_API_KEY`, "Trending on YouTube" (the country's music chart) leads Home, YouTube songs lead Search, and every genre page has a YouTube shelf. Music only: category *Music*, embeddable, 1–15 minutes (no Shorts, mixes or livestreams). Songs play in **YouTube's official embedded player**, so the video stays on screen, there's no background or audio-only playback, and YouTube may show its own ads, as its terms require. Includes Up next, like, and live lyrics. The screen looks like a music player: the video sits in the album-art square, with Spotineil's own seek bar and controls. **Picture-in-picture:** press Home or open Recents and the song keeps playing in a floating video window with ⏯ / ⏭. Closing that window or turning the screen off pauses it. |
| **Free app (default, no account)** | Opens straight into **Home / Search / Your Library**, no login needed. **Home:** filter chips (All / Music / Radio / Podcasts), "Jump back in" tiles, and shelves of big cover cards. **Search:** one search across every source, plus a colored "Browse all" grid of genres and categories. **Your Library:** Liked Songs, music on this device, followed podcasts, saved stations, recently opened albums. |
| **Free full songs** | [Audius](https://audius.co) (trending, rising artists, genres), free live concerts from the [Internet Archive Live Music Archive](https://archive.org/details/etree) (bands that allow taping and sharing), free netlabel albums, [ccMixter](https://ccmixter.org) Creative Commons songs, and [Jamendo](https://www.jamendo.com) when a key is set. A download button appears only when the artist or licence allows it. |
| **Podcasts** | Top shows and search through the Apple Podcasts directory. Full episodes stream from each show's own public RSS feed. Follow shows to keep them in Your Library. |
| **Radio** | Thousands of live stations from [Radio Browser](https://www.radio-browser.info), popular stations in your country, genre radio, and saved favorites. |
| **Previews & charts** | Official 30-second clips of mainstream hits from Deezer (iTunes as a fallback). With an optional Last.fm key, Last.fm's global chart is matched to these previews too. |
| **Liked Songs without an account** | Tap **+** on the mini player or the heart on the full player to save any free song, preview or local file on the phone. |
| **Lyrics** | Synced lyrics from LRCLIB for every kind of playback, with a live two-line lyrics card under the player controls and a full-screen view. Previews are matched by the full song's length, and their lyrics are shown unsynced because a clip starts mid-song. |
| **Your music** | Songs / Albums / Artists views for audio files on your phone. |

### What Spotineil does *not* do

Spotineil doesn't block or skip ads, rip or download Spotify (or YouTube or other) audio, or install third-party "extensions" that pull music from unlicensed sources. For ad-free, unlimited Spotify playback and offline downloads of Spotify songs, use **Spotify Premium** through the official app.

## Screenshots

> _Add your own screenshots to `docs/screenshots/` and link them here._

| Home | Search | Library | Player | Lyrics |
|---|---|---|---|---|
| _home.png_ | _search.png_ | _library.png_ | _player.png_ | _lyrics.png_ |

---

## Setup

### Requirements

- Android Studio (Ladybug or newer) or a JDK 17 and the Android SDK (platform 35)
- An Android device running Android 10 (API 29) or newer, with the **official Spotify app installed and logged in** (needed for playback through App Remote)
- A Spotify account. Premium is recommended (see [Free vs Premium](#free-vs-premium))

### 1. Create a Spotify developer app

1. Go to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and click **Create app**.
2. Under **Redirect URIs**, add exactly:
   ```
   spotineil://callback
   ```
3. Under **Which API/SDKs are you planning to use?**, select **Web API** and **Android**.
4. In the app's settings, add your **Android packages**. Both variants need an entry:

   | Package name | Variant |
   |---|---|
   | `com.spotineil.app.debug` | debug builds |
   | `com.spotineil.app` | release builds |

   Each entry needs the **SHA-1 fingerprint** of the key that signs that variant. To print it:
   ```bash
   ./gradlew :app:signingReport
   # or, for the default debug keystore:
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   App Remote rejects connections from packages or signatures that aren't registered here.
5. Copy the **Client ID**. You do **not** need the client secret, and you should never put it in the app.

### 2. Configure the build

Add the Client ID to `local.properties` in the project root. This file is git-ignored, so never commit it:

```properties
sdk.dir=/path/to/Android/sdk
SPOTIFY_CLIENT_ID=your_client_id_here
```

The build reads it into `BuildConfig.SPOTIFY_CLIENT_ID`. CI reads the `SPOTIFY_CLIENT_ID` environment variable instead.

**You can skip this step.** The app opens in the free, no-account mode by default (free songs, concerts, podcasts, radio, previews, local files, lyrics). Spotify is only used if you log in from **Settings → Log in with Spotify**. Spotify now requires the developer account to have **Premium** for Web API access.

**Optional keys** (all free), also in `local.properties`:

```properties
JAMENDO_CLIENT_ID=your_jamendo_client_id   # https://devportal.jamendo.com — adds Jamendo songs
LASTFM_API_KEY=your_lastfm_api_key         # https://www.last.fm/api/account/create — adds the Last.fm chart
YOUTUBE_API_KEY=your_youtube_data_api_key  # Google Cloud Console → YouTube Data API v3 — the main catalog
```

**YouTube quota and the request gateway.** A YouTube key gets 10,000 quota units a day. The music chart costs 1 unit; a search costs about 101 (search + one details call). Spotineil's gateway keeps usage low:
- It caches results in memory and Room (chart for 6 h, searches for 24 h).
- Identical requests that are in flight share one call.
- At most 2 calls run at once.
- Units are counted per Pacific-time day, against a 9,000-unit budget.
- When the budget or Google's quota runs out, it serves cached results and falls back to the other free sources. Search shows how many YouTube searches are left today.

It does **not** rotate keys or projects, because Google's terms forbid getting around quota that way.

**Restrict the key** in Google Cloud Console:
- **Application restrictions → Android apps:** add `com.spotineil.app.debug` and `com.spotineil.app` with their SHA-1s. Spotineil sends the matching `X-Android-Package` / `X-Android-Cert` headers.
- **API restrictions:** allow YouTube Data API v3 only.

An APK always contains its key, so restriction is what keeps a copied key useless elsewhere.

Last.fm hosts no audio, only charts and metadata, so its chart plays as official previews. Audius, the Internet Archive, ccMixter, Apple Podcasts, Radio Browser, Deezer, iTunes and LRCLIB need no keys.

### 3. Build and run

```bash
./gradlew assembleDebug          # build the APK
./gradlew installDebug           # install on a connected device
./gradlew testDebugUnitTest      # unit tests + Robolectric Compose UI tests
```

The Spotify App Remote AAR isn't published to Maven. Gradle downloads it from the [official GitHub release](https://github.com/spotify/android-sdk/releases) through an Ivy repository declared in `settings.gradle.kts`, so no binary is committed to this repo. Your first build needs network access.

### Development mode: only allow-listed users can log in

New Spotify apps start in **Development mode**. In this mode, only users you add under **User Management** in the dashboard can log in. Anyone else gets an error from Spotify's login page, even with a valid build.

- Add each tester's Spotify account email to **User Management**.
- Spotify limits how many users a Development-mode app can have and may change the rules (including requirements for the app owner's account). Check the dashboard and [Spotify's quota-mode docs](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) for the current limits.
- To open the app to the public, request an **Extended Quota** review from Spotify.

---

## Free vs Premium

Spotineil reads your plan (`product`) from `/v1/me` and App Remote's `canPlayOnDemand` capability, and **respects Spotify's limits**:

| | Spotify Premium | Spotify Free |
|---|---|---|
| Browse, search, library, playlists, stats, lyrics | ✅ | ✅ |
| Playback through the Spotify app (App Remote) | ✅ Full on-demand control | ⚠️ Spotify applies Free rules (shuffle-play contexts, limited skips) |
| Spotify Connect control of other devices (Web API `/me/player`) | ✅ | ❌ Spotify returns `403 PREMIUM_REQUIRED` |
| Local files on the device | ✅ | ✅ |

Free users see a short in-app notice wherever controls are limited. Spotineil never tries to bypass these restrictions.

## Restricted Web API endpoints

Spotify restricts several endpoints for apps created after late 2024, including recommendations, related artists, audio features/analysis, and featured or category playlists. **Spotineil doesn't depend on any of them.** Every optional section (for example new releases, browse categories, or an artist's top tracks) is wrapped so that a `403`/`404` hides that section instead of failing the screen.

---

## Architecture

MVVM + Clean Architecture, split into modules:

```
:app                 Navigation, DI wiring, theming, login (AppAuth), Glance widget, Android Auto manifest
:core:model          Plain Kotlin domain models (Track, Album, Artist, Playlist, User, Lyrics, …)
:core:common         Dispatchers, application scope, Spotify app config
:core:network        Retrofit/OkHttp + kotlinx.serialization: Web API, Accounts (PKCE), LRCLIB;
                     auth interceptor, refresh Authenticator, 429 handling, error mapping
:core:data           Repositories, Room cache (+ Paging RemoteMediator), DataStore settings,
                     Tink-encrypted token store, LRC parser
:core:player         PlaybackController façade over App Remote, Spotify Connect (Web API) and
                     Media3 (ExoPlayer + MediaLibraryService for local files / Android Auto)
:core:designsystem   Theme (dark-first, AMOLED, accent colors, Manrope), shared Compose components
:core:ui             Shared feature UI (track context menu, Free-tier notice)
:core:testing        Test fakes and rules
:feature:home | search | library | player | artist | album | playlist | profile | settings | discover
```

- **DI:** Hilt. **Async:** Coroutines + Flow. **Images:** Coil. **Navigation:** Compose Navigation with type-safe `@Serializable` routes.
- **Build:** Gradle Kotlin DSL, a version catalog (`gradle/libs.versions.toml`), and convention plugins in `build-logic/`. JDK 17.
- **Offline-first:** Room caches the library, home feed, detail pages, lyrics and recent searches. Screens render cached data immediately and then refresh from the network.
- **Reliability:** On `401`, a single synchronized token refresh runs, then the request is retried. On `429`, short `Retry-After` waits are retried transparently and long ones are shown as an error. Network errors show a retry UI. All list endpoints use Paging 3.

### Playback routing

```
PlaybackController
 ├─ local / free / radio?    → LocalEngine (Media3 MediaController → LocalPlaybackService)
 ├─ App Remote connected?    → SpotifyRemoteEngine (official Spotify app on this device)
 └─ otherwise                → Spotify Connect via Web API (/me/player/*), state polled while visible
```

## Testing

| Layer | Tests |
|---|---|
| Network | Token refresh through the `Authenticator` (MockWebServer), proactive refresh, rejected refresh token, 429 `Retry-After`, error mapping, lenient JSON parsing |
| Data | LRC parser, LRCLIB matching and caching, recent searches, home feed partial failure |
| ViewModels | Search debounce, error and retry (Turbine); Home offline-first; Player like toggle, Free notice, position interpolation |
| Compose UI | Player (mini, full, controls, restrictions, lyrics tap-to-seek) and Search (typing, chips, results, empty state) run on Robolectric, so CI doesn't need an emulator |

```bash
./gradlew testDebugUnitTest
```

## CI / Releases

- **`.github/workflows/ci.yml`** builds the debug APK and runs all unit tests on every push and pull request.
- **`.github/workflows/release.yml`** runs on `v*` tags. It builds a signed, minified release APK and attaches it to a GitHub Release.

Repository secrets used by the release workflow:

| Secret | Purpose |
|---|---|
| `SPOTIFY_CLIENT_ID` | Spotify app Client ID (also optional for CI) |
| `SPOTINEIL_KEYSTORE_BASE64` | `base64` of your release `.jks` |
| `SPOTINEIL_KEYSTORE_PASSWORD` | Keystore password |
| `SPOTINEIL_KEY_ALIAS` | Key alias |
| `SPOTINEIL_KEY_PASSWORD` | Key password |

> On Windows, mark the wrapper as executable before pushing: `git update-index --chmod=+x gradlew`. The workflows also `chmod` it.

Remember to register the **release** key's SHA-1 for `com.spotineil.app` in the Spotify dashboard.

## License

[MIT](LICENSE). The Manrope font is under the SIL Open Font License 1.1. The Spotify Android SDK is downloaded at build time under its own license and the Spotify Developer Terms.
