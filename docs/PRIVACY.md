# Spotineil privacy policy

_Last updated: 22 November 2026_

Spotineil is a free, open-source Android music app. This page explains what it does with your data.

## What the app collects
**Nothing about you is sent to the developer.** Spotineil has no accounts, no analytics, no ads and no tracking.

## What stays on your phone
These are stored only on your device. They are deleted when you uninstall the app or clear its data:
- your Liked Songs, followed podcasts, favourite radio stations and play history;
- where you stopped in each song, and your recent searches;
- temporary copies of song lists (cache) so the app loads faster and uses less data;
- your settings (theme, accent colour, language).

## Karaoke "vocals off"
Removing the singer's voice happens entirely on your phone, with an AI model stored in the app's private storage. The song is processed in memory while it plays; no audio is recorded, saved or sent anywhere.

## Services the app talks to
To show and play music, the app sends requests (such as your search words and your approximate country) directly from your phone to the services below. Each one handles those requests under its own privacy policy. The developer does not see them.
- **YouTube Music**: the music catalog, search, charts and audio streams. When you tap Download on a YouTube song, its audio is fetched the same way and saved on your phone (in Music/Spotineil, or the folder you picked in Settings); nothing is uploaded.
- **LRCLIB**: song lyrics (the song name, artist, album and length). With "Lyrics on the lock screen" on, this happens for each song as it starts playing, so its lines can show on the lock screen; answers are kept on the phone.
- **Google Translate** (only when you turn on "Translate lyrics"): the lyric lines of the song, to translate them into your language.
- **Last.fm**: similar artists, used for radio and "Play similar".
- **Audius, Jamendo, ccMixter, Internet Archive**: free and Creative Commons music.
- **Radio Browser**: the radio station directory.
- **Apple Podcasts directory** and each podcast's own RSS feed.
- **Apple Music** (only while "Animated artwork" is on, and not with Data saver): the name and artist of the song playing, sent to Apple's public iTunes search to find its album, then that album's public page on music.apple.com and its moving-cover video. Answers are kept on the phone for a week.
- **Spotify (only if you choose to connect it)**: see below.
- **GitHub**: when the app opens it asks GitHub whether a newer version exists, and downloads it only if you agree. After an update it reads that version's release notes for "What's new". The first time you turn vocals off in Karaoke mode, the voice model is downloaded from this project's GitHub releases (once, after you agree).

## Crash reports
If the app crashes, the error is saved on your phone only. The next time you open the app it asks whether to send it. Only if you tap **Send report** does it open a GitHub issue in your browser, filled in with the app version, Android version, phone model and the error, which you can read and edit before posting. Nothing is sent automatically.

Spotineil does not access your Google or YouTube accounts. Signing in is never required.

## Connecting Spotify (optional)
If you tap **Connect Spotify**, you either paste your own `sp_dc` cookie (copied from open.spotify.com on a computer) or sign in on Spotify's own web page inside the app. Your password and cookie go only to Spotify. Spotify's session cookie (`sp_dc`) and the short-lived access token stay on your phone (the cookie in the app's web storage) and are sent only to Spotify, to read your playlists and Liked Songs. Nothing is sent to the developer. **Settings → Account → Spotify → Disconnect** deletes the cookies and the token. This uses Spotify's unofficial web-player route, which Spotify's terms do not allow for apps, so use it at your own risk.

## Contact
Questions: open an issue on the project's GitHub page, <https://github.com/freshmens12/Spotineil/issues>.
