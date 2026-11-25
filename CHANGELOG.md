# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.16.0] - 2026-11-25

### Added
- **Vocals off in Karaoke mode (AI)**: the mic button next to Close takes the singer's voice out of the song so you can sing over the real music. An AI model on your phone (Deezer's Spleeter) finds the voice and only the voice is removed; the instruments stay as they are. The first time, it asks to download the model (39 MB, once; then it works offline). Turning vocals off pauses the music for a moment while the next part of the song is prepared, and it stays off from song to song until you turn it back on or leave Karaoke mode. Nothing is recorded or saved: the song is processed in memory while it plays.

### Changed
- Karaoke mode stays open when the next song starts, and says so when a song has no lyrics.
- The app download is bigger (about 31 MB) because it now includes the AI engine that runs the voice model on the phone.

## [1.15.0] - 2026-11-25

### Changed
- **Spotineil now has its own release key and an optimized build.** The download is about 10 MB instead of 90 MB, and the app starts and runs faster. Updates from now on are signed with Spotineil's own key, which is kept safely off GitHub.
- Because of the new key, 1.15.0 installs as a new app next to Spotineil 1.14.0 or older (only this once). When it opens, it shows how to bring your Liked Songs, playlists and history across: in the old app, Settings → Backup & restore → Back up; here, Restore that file; then uninstall the old app with one tap.

## [1.14.0] - 2026-11-23

### Added
- **Crash reports you control**: after a crash, the next start asks whether to send the report. "Send report" opens a GitHub issue already filled in (app version, Android version, phone model and the error) that you can read before posting; nothing is sent on its own.
- **What's new**: the first start after an update shows what changed in that version.
- **Device tests before each release**: every push now also starts the app on an Android emulator on GitHub, walks Home, Search and Your Library, and plays a test sound through the real player.

## [1.13.0] - 2026-11-23

### Added
- **Lyrics extras**: long-press a lyric line to share it as a picture; **Karaoke mode** (full-screen button in the lyrics view) shows the current line large with the next one below.
- **Car mode** (car button on the player): just the song and very large controls.
- **Like from the notification and lock screen**: a heart button on the media notification.
- **Android Auto**: Liked Songs, Your playlists, Recently played and On this device; picking a song plays the whole list from there.
- **Voice search** (microphone in the search box) and a **filter box** in Liked Songs and History.
- **Playlists widget**: Liked Songs and your playlists on the Home screen; one tap shuffles one.
- **Translated lyrics**: the translate button in the lyrics view shows each line in your app language (English, Spanish or Tagalog) under the original, using Google Translate.

### Changed
- Playlists in Moods and genres, and on artist pages, now just say "Playlist" under their name.

## [1.12.0] - 2026-11-23

### Added
- **Made for you** on Home: "Your top artists" (last 30 days), "Because you liked …" (songs by artists like your most-liked one) and "Rediscover" (songs you played a lot but not lately).
- **Artist pages**: tap the artist on the player (or "Go to artist" in the song menu) for their popular songs, albums and singles, similar artists and playlists they are in.
- **Album and playlist pages** for YouTube Music albums and playlists.
- **Moods and genres** in Search: YouTube Music's moods and genres (OPM, Chill, Workout, …) with their playlists.
- **Spotineil Wrapped** in Your Library: minutes listened, top songs and top artists for this month, this year or all time, with a shareable card. Listens are counted on the phone only (songs played for at least 30 seconds) and are included in Backup.

## [1.11.0] - 2026-11-23

### Added
- **Equalizer** (Settings → Playback): Flat, Bass boost, Vocal, Rock, Pop, Treble or your own 5 bands.
- **Volume leveling**: evens out loud and quiet songs (Android 9+).
- **Skip silence** and **Podcast speed** (0.75× to 2×; music always plays at normal speed).
- **Sleep timer fade-out**: the music fades out over the last 30 seconds instead of stopping suddenly.
- **Gapless albums**: no crossfade between songs of the same album, so live albums and continuous mixes run straight on.
- **Playlist radio**: the radio button on your playlists and Liked Songs plays songs by their artists and similar ones.

## [1.10.2] - 2026-11-23

### Added
- **Your Spotify playlists** on Home, above "Trending music in Spotineil", when Spotify is connected: Liked Songs first, then your playlists, with the same big cards.

### Fixed
- Spotify playlists no longer show "0 songs" in the list (Spotify's list has no counts); the real count is shown when you open a playlist.

## [1.10.1] - 2026-11-23

### Fixed
- **Connect Spotify:** Spotify's public API now refuses web-player tokens ("429 QUOTA_EXCEEDED"), which is why connecting with a cookie always said it didn't work. Spotineil now reads your profile, playlists and Liked Songs through the same queries open.spotify.com itself uses, and keeps those queries up to date by itself.

## [1.10.0] - 2026-11-23

### Added
- **Your playlists:** create, rename, delete and reorder (drag) your own playlists in Your Library.
- **Song menu:** long-press any song for Play next, Add to queue, Add to playlist and Like. "Add to playlist" is also on both player screens.
- **Save Spotify playlists:** "Save to Your Library" on a Spotify playlist matches every song once and keeps a copy in Your playlists that opens instantly and works without Spotify.
- **Backup & restore** (Settings): save Liked Songs, playlists, history, stations, podcasts, recent searches and settings to a file, and bring them back on any phone.
- **Downloads** in Your Library: the songs you downloaded, with how much space they use; delete them from there.
- **Queue:** remove songs with ✕ and "Save as playlist".

### Fixed
- Audius songs that can only be downloaded by following the artist no longer show a download button that fails.
- **Connect Spotify:** Spotify's robot check refuses its sign-in page inside apps ("Oops! Something went wrong"). You can now connect by pasting your own `sp_dc` cookie from open.spotify.com on a computer (steps are shown in the app); the sign-in page stays as a second option.

## [1.9.0] - 2026-11-22

### Added
- **Connect Spotify** (Settings → Account, the Welcome screen, or Your Library → Spotify): sign in to Spotify's web player inside the app to see your private playlists and Liked Songs. Songs are matched and played from YouTube Music, so Spotify Premium is not needed. This uses Spotify's unofficial web-player route; see the privacy page.

### Fixed
- **Crossfade / AutoMix** no longer pause for a moment near the end of a song: the old song is now handed to a second player first, then the next one fades in.

## [1.8.0] - 2026-11-22

### Added
- **Song transition** (Settings → Playback): Off, **Crossfade** (6 s) or **AutoMix** (a longer, smoother blend on an equal-power curve; it does not match beats).
- **Tagalog** language, and the language setting now reads **Automatic** (follows the phone) by default.
- **On this device:** delete a song from the phone (the ⋮ button or a long press). Android asks you to confirm.
- **Share Spotineil** in Settings: opens the share sheet with the download link.
- **About** screen: version, source code (GitHub) and open-source licenses.
- **Recent searches** under the search box (tap to search again, ✕ to remove one, Clear all).
- **Songs continue where you left them:** play a song again and it picks up from where you stopped (songs left in their last 10 seconds start over).

### Fixed
- Soft glass: the full player no longer shows the page behind it, and its play/pause icon is visible.
- Lyrics now use one look: the large, spaced lines of the full lyrics view (the cramped preview under the controls is gone).

## [1.7.0] - 2026-11-22

### Added
- **Soft glass** theme (Settings → Theme): a soft coloured backdrop with translucent panels.
- **Trending music in Spotineil** on Home is now filled from YouTube Music's trending chart for your country. YouTube Music removed the "Top songs" list from its charts page, so the app reads the chart playlist instead.
- **Support the developer** button in Settings, and a full credits screen (every library and service, with licenses).
- Releases and updates now come from this repository's GitHub Releases (the separate downloads repository is retired).

### Changed
- The project license is now **GPL-3.0** (it uses the GPL-3.0 InnerTube X library).
- Removed the Audius "Trending" shelf from Home so the trending shelf is YouTube Music only.
- Removed the widget list from Settings; widgets are added from the Home screen.

### Fixed
- Switching theme no longer jumps back to Home.
- No built APKs or keys are kept in the repository any more.

## [1.6.0] - 2026-11-22
### Added
- Open-source licenses screen lists every library and service. Support button in Settings.

## [1.5.0] - 2026-11-21
### Added
- Five home-screen widgets: Player (2×2), Now playing (4×1), Mini player (2×1), Quick actions (4×1) and Recently played (4×2). Widgets redraw together when the song or play/pause changes, and Play on a widget continues your last session.

## [1.4.0] - 2026-11-21
### Added
- "Connect to a device" now works without Spotify: it lists the phone's outputs (speaker, Bluetooth, wired, USB, HDMI) and opens Android's output picker, Bluetooth pairing and cast.
- Search now finds songs as you type; coming back from a song keeps the results without searching again.
### Fixed
- Lyrics no longer hidden behind the album art; "Session expired" message while playing.

## [1.3.0] - 2026-11-20
### Added
- History of every song you played; search results stay when you come back from a song; the Home "Continue listening" card hides while a song is playing.

## [1.2.0] - 2026-11-20
### Added
- **In-app updates:** the app checks for a newer release when it opens and, with your permission, downloads and installs it.
- Audio-only playback from YouTube Music through InnerTube X (no video player); full songs for search, charts, radio and autoplay.

## Earlier development


### Changed — first launch
- New **Welcome** screen on first launch: the app's three main features and one **Get started** button. It shows once; after that the app opens straight into Home.
- Removed "Log in with Spotify" (Welcome screen and Settings) and "Get Spotify on Google Play". The free app needs no account.
- Settings → About text now describes the free sources instead of the Spotify Web API.

### Added — YouTube that feels like a music app
- **Song-style results:** official "Artist – Topic" audio tracks (studio version with album cover) are ranked up in search, lyric re-uploads, live versions, covers and karaoke sink (unless that's what you searched for), and duplicate versions of the same song collapse into the best one. Rows say **Song** or **Video**.
- **Start radio** button on the YouTube player: Up next becomes songs by the current artist and similar artists (from Last.fm), mixed so the same artist doesn't play twice in a row. One YouTube search per station (cached for a day).
- **Autoplay:** when the last song in the queue starts, a radio is lined up after it, so the music doesn't stop. It's skipped when fewer than ~20 searches are left today.
- **Continue listening:** the YouTube queue, song and position are saved (also after the app is closed). A Home card continues from the exact spot.
- **Play similar:** one tap finds songs by the same artist, or in the artist's genre (Last.fm tags), on Audius and Jamendo, and plays them with the screen off. Uses no YouTube quota.
- Previous restarts the song after its first 3 seconds; the next song starts straight from the player's "ended" callback.
- `docs/youtube-quota-extension.md` (answers for Google's free quota-increase form) and `docs/PRIVACY.md`.
- 5 tests for song-style ranking, radio mixing and title decoding (75 total).

### Fixed — YouTube titles
- Titles from the Data API showed HTML codes (`I&#39;m`); they're now decoded, including in cached results.

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
