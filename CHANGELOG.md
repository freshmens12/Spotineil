# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added — Jamendo on Home
- "Popular on Jamendo" Home shelf (full Creative Commons songs, this week's most popular) when `JAMENDO_CLIENT_ID` is set.

### Fixed — free-source reliability
- Jamendo sometimes answers "success" with zero results for a request that works a moment later. Empty answers are now retried up to 3 times (Home and Search), so the shelf no longer disappears at random.
- A song whose audio file can't be loaded (e.g. a Jamendo track whose file returns 404) is skipped and the next song plays, instead of the queue stopping. After 5 failures in a row (likely offline) playback stops and shows the error.

### Added — YouTube picture-in-picture and music-player layout
- **Picture-in-picture** for YouTube songs:
  - Pressing Home or opening Recents during a YouTube song shrinks it into a floating video window with ⏯ / ⏭ buttons (auto-enter on Android 12+, `onUserLeaveHint` on 10–11).
  - The window shows only the video. The bottom bar and mini player are hidden.
  - Playback pauses when the window is closed or the screen turns off (activity stopped). YouTube's terms forbid background play, so it isn't enabled.
  - `MainActivity` handles resize config changes, so the player survives the switch.
- **Music-player layout** for the YouTube screen:
  - The video sits in the album-art square over its blurred thumbnail, never below 200 px tall.
  - The art area is pinned so the video is always visible.
  - Spotineil's own seek bar and controls replace YouTube's control bar (`controls=0`).
  - Lyrics and Up next scroll underneath.
- The player view stays in one place in the UI tree and is only moved and resized. Re-parenting it detached the web player and paused the song.

### Added — YouTube as the main catalog
- YouTube Data API v3 (`YOUTUBE_API_KEY`):
  - "Trending on YouTube" (country music chart) is the first Home shelf.
  - "Songs on YouTube" leads Search results.
  - Genre and Charts pages get YouTube shelves.
  - Results are music only: category 10, embeddable and syndicated, 1–15 min, no livestreams.
- YouTube player screen built on YouTube's official IFrame player (android-youtube-player 13.0.0, Apache-2.0):
  - Shows the video, with YouTube's own controls kept.
  - Previous / play-pause / next, auto-advance, Up next, Like (saved with other Liked Songs), and an LRCLIB lyrics card.
  - Pauses in the background. Background playback is not enabled.
- **Request gateway** for the YouTube key:
  - Memory and Room cache (6 h chart, 24 h search), with stale fallback.
  - In-flight request coalescing, and at most 2 concurrent calls.
  - A per-day (Pacific) quota budget with a "searches left today" note, and quota-exceeded handling that falls back to the other free sources.
- `X-Android-Package` / `X-Android-Cert` headers so the key can be restricted to Spotineil. The key is excluded from HTTP debug logs.
- 6 tests for the gateway (filtering, coalescing, budget, quota fallback, title parsing): 70 total.

### Fixed
- Four strings in the Discover module had doubled backslashes (`\\'`) and broke resource compilation.
- Generic server-error message no longer mentions Spotify.

### Changed
- **The app now opens straight into the free app (no login).** The Spotify login moved to Settings. Logging out returns to the free app.
- New look modelled on a classic music-app layout:
  - A profile button next to the filter pills, and 2-column "Jump back in" tiles.
  - Big cover-card shelves, a colored "Browse all" grid, and white search field.
  - An artwork-tinted mini player with **+** (like) and play/pause, and a Home / Search / Your Library bar over a fade.
  - Default accent is green again. The glass/Haze effect from the previous build was removed.

### Added
- **Free sources:** Internet Archive live concerts (Live Music Archive) and netlabel albums, ccMixter Creative Commons songs, Audius rising artists and genre charts.
- **Podcasts:** Apple Podcasts top shows and search. Episodes are parsed from each show's public RSS feed (SAX, external entities disabled). Follow shows and play them with a queue.
- **Liked Songs without an account** (Room): like any free song, preview or local file from the player.
- **"Jump back in" history** (Room) for songs, stations, podcasts and albums. Tiles stay put while Home is on screen.
- **Last.fm chart** (optional `LASTFM_API_KEY`), matched to official previews.
- **Lyrics card** under the player controls (current + next line, tap to open). Previews are matched to LRCLIB by the full song length and shown unsynced.
- Browse pages for genres (Audius genre + genre radio), concerts, free albums, radio, podcasts and charts.
- Album/concert and podcast detail pages with artwork-tinted headers.
- Home shelves retry automatically after network or server errors.
- Tests for the podcast feed parser, Internet Archive mapping, and liking free songs (64 total).

### Fixed
- The Spotify Connect poller no longer calls the Web API (`/me/player`) when no Spotify account is logged in.
- The Desktop emulator launcher uses public DNS (`-dns-server 8.8.8.8,1.1.1.1`), fixing hosts that failed to resolve on Windows.

### Added (first free-sources pass)
- **Discover** tab with three legal, free sources that work without Spotify:
  - Free music: full tracks from Audius, plus Jamendo when `JAMENDO_CLIENT_ID` is set. Downloads go to `Music/Spotineil`, only when the artist or licence allows it.
  - Radio: Radio Browser stations, popular in your country, genre chips, search, and favorites (Room). Mirrors fail over automatically.
  - Previews: official 30-second clips from Deezer, falling back to iTunes.
- **Guest mode** ("Continue without Spotify"): Discover + Your music with no account; log in with Spotify later from Settings.
- **Your music:** Songs / Albums / Artists views for files on the device.
- Generic `PlaybackController.playMedia()` for streaming any URL through Media3. Live streams disable seeking.
- Network security config that allows plain-HTTP radio streams (API traffic stays on HTTPS).
- New look: a floating frosted-glass bottom bar (Home / Discover / Your Library, with a separate round Search button) and a pill-shaped glass mini player with play and next. Built with [Haze](https://github.com/chrisbanes/haze); Android 10–11 gets a translucent fill instead of blur.
- Home: bold greeting with an avatar (photo or initial), All / Music / Podcasts filter chips, 8 quick-access tiles starting with Liked Songs, and a "Your podcasts" row of large cards.
- New default accent color: Rose. Change it in Settings → Accent color.

### Fixed
- Text on the login screen rendered black on dark backgrounds (missing root `Surface`).
- Media3 controller was accessed off the main thread when routing playback commands.
- Widget controls now reconnect App Remote after a cold start instead of falling back to the Premium-only Web API.

## [1.0.0] - 2026-11-18

### Added
- Spotify login with Authorization Code + PKCE (AppAuth, Custom Tabs). No password handling and no client secret.
- Tokens stored in DataStore and encrypted with Tink AES-256-GCM, wrapped by an Android Keystore key. Tokens refresh automatically through an OkHttp `Authenticator`.
- Web API layer (Retrofit + kotlinx.serialization) that handles 401 (refresh), 429 (`Retry-After`), 403/404 for restricted endpoints, and network errors.
- Home: time-of-day greeting, quick picks, recently played, top artists and tracks, playlists, new releases, saved albums, pull-to-refresh, skeleton loaders, and offline cache.
- Search: debounced search across tracks, artists, albums, playlists and podcasts, with filter chips, paginated results, recent searches (Room) and a browse-categories grid.
- Library: playlists, albums, artists and podcasts tabs; sorting by recent, A–Z or creator; grid/list toggle; Liked Songs; local files. Room + Paging 3 `RemoteMediator` keep it offline-first.
- Album, artist, playlist and profile pages with a collapsing header, Palette-based dynamic color, and play/shuffle.
- Track context menu: add to queue, add to playlist, like, go to artist or album, share.
- Playlist management: create, rename, edit description, public/collaborative, follow, remove tracks, drag-to-reorder.
- Player: a mini player above the bottom nav that expands into a full player with a shared-element transition, blurred artwork, seek bar, like, queue (reorderable for local files), Connect device picker, and sleep timer.
- Playback through the Spotify App Remote SDK, with Spotify Connect (Web API) as a fallback. Local files play through Media3 ExoPlayer and `MediaLibraryService`, with notification, lock-screen, Bluetooth and Android Auto support.
- Synced lyrics from LRCLIB (matched by track, artist, album and duration) with auto-scroll and tap-to-seek.
- Stats: top tracks and artists for 4 weeks, 6 months and all time.
- Settings: theme (System/Light/Dark/AMOLED), accent color, data saver, language (English/Spanish), clear cache, log out, About and licenses.
- Glance home-screen widget with now playing and playback controls.
- Original adaptive launcher icon (including a themed/monochrome layer) and Manrope typography.
- Friendly notice for Spotify Free users where on-demand control is limited.
- Unit tests (repositories, token refresh, LRC parser, ViewModels with Turbine, MockWebServer), plus Robolectric Compose UI tests for the player and search.
- GitHub Actions workflows: build and unit tests on every push; signed release APK on `v*` tags.
