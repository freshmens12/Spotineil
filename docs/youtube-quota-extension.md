# YouTube API quota extension: answers for Google's form

Google gives every project **10,000 quota units a day** for free. Spotineil spends **101 units per search** (one `search.list` call plus one `videos.list` call), so that's about 89 searches a day for everyone using your API key. You can ask Google for more, for free, with the
**YouTube API Services – Audit and Quota Extension Form**:
<https://support.google.com/youtube/contact/yt_api_form>

Google reviews the app against the [YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies) before raising the limit. The review can take a few weeks, and Google may ask follow-up questions or for a short screen recording.

Copy the answers below into the form. Replace everything in `[brackets]`, and answer honestly where your situation differs (for example the number of users).

---

## Before you start

1. **Privacy policy link.** The form asks for a public URL. Your GitHub repo may be private, and then reviewers can't open it. Make the repo public, or paste [`PRIVACY.md`](PRIVACY.md) into a public page (a GitHub Gist works) and use that link.
2. **Screen recording (recommended).** A 1–2 minute phone recording: open Spotineil → search a song → it plays in the YouTube player → Start radio → leave the app (the video shrinks into picture-in-picture). Upload it to Google Drive with link sharing on.
3. **Project number.** Google Cloud Console → your project → Dashboard → "Project number".

---

## Answers

**Organization / company name**
> [Your name], student project at [your school]

**Is your API client used internally or publicly?**
> Internal / not publicly released. It's a school project used by the developer and a small group of testers.

**API client name and description**
> Spotineil is an Android music app built as a school project (Kotlin, Jetpack Compose). It shows music from free, legal sources: YouTube, plus Creative Commons music (Jamendo, Audius, Internet Archive), internet radio and podcasts. YouTube is used to find songs (search and the most-popular music chart). Every YouTube song plays in YouTube's official embedded IFrame player.

**Which YouTube API services do you use?**
> YouTube Data API v3 only: `search.list` (music category, embeddable videos) and `videos.list` (the most-popular music chart, and duration and embeddable status for search results). No OAuth and no access to users' YouTube accounts. Read-only public data.

**How does your app use YouTube data? (feature list)**
> - Search: users search for a song; results come from `search.list`, then one `videos.list` call checks duration and embeddable status.
> - Trending: the country's most-popular music chart (`videos.list` with `chart=mostPopular`, `videoCategoryId=10`).
> - Radio: when a user starts a radio (or the queue ends), one `search.list` call finds songs by the current artist and similar artists.
> - Playback: always through the official YouTube IFrame player (android-youtube-player library). The video is visible on screen, or in Android picture-in-picture when the user leaves the app. Playback pauses when the player is no longer visible.

**How do you comply with the YouTube API Services Terms and Developer Policies?**
> - Videos play only in YouTube's official embedded player, at least 200×200 px, never hidden, covered, or modified. YouTube's ads are not blocked.
> - No downloading, audio extraction, separating audio from video, or background audio-only playback of YouTube content.
> - YouTube results are clearly labelled as YouTube in the app.
> - API data is cached only briefly to save quota (charts 6 hours, searches 24 hours), well under the 30-day limit, and never shared outside the app.
> - The API key is restricted to the Android app (package name and SHA-1 certificate fingerprint) and to the YouTube Data API v3.
> - The app collects no personal data and has no user accounts; see the privacy policy.

**Current quota and how much you need**
> Current: 10,000 units/day. Requested: [30,000] units/day.

**Why do you need more quota? (justification)**
> Each search costs 101 units (`search.list` 100 + `videos.list` 1), so the default quota allows about 89 searches a day for all testers combined. One tester uses about 20–30 searches a day (searching plus radio), so the default quota runs out with 3–4 testers. The app already reduces usage: it caches results (6–24 hours), coalesces duplicate requests, limits itself to 2 concurrent calls, keeps a daily budget, stops calling the API when the budget is reached, and shows other free sources instead. [30,000] units/day would support about [10] testers.

**Expected number of users**
> About [10] testers (students and the instructor). The app is not on Google Play.

**Link to the app / demo**
> [Link to your screen recording, or "Not publicly available: Android APK for testers only."]

**Privacy policy URL**
> [Public link to PRIVACY.md, see "Before you start"]

**Google Cloud project number**
> [Your project number]

---

If Google replies with questions, answer them directly and honestly. If they ask for changes to the app, send them to me and I'll help you make them.
