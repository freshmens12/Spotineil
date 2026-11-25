package com.spotineil.core.model

enum class ThemeMode { System, Light, Dark, Amoled, Glass }

/** Preset accent colors (ARGB). The first one is the default Spotineil green. */
enum class AccentColor(val argb: Long) {
    Rose(0xFFFF4D6D),
    Green(0xFF1ED760),
    Teal(0xFF2DD4BF),
    Blue(0xFF4F8CFF),
    Purple(0xFFA78BFA),
    Pink(0xFFF472B6),
    Orange(0xFFFB923C),
    Yellow(0xFFFACC15),
}

/** How one song hands over to the next. */
enum class TransitionMode { Off, Crossfade, AutoMix }

/**
 * Equalizer presets as gains in dB for 5 bands (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz).
 * [Custom] uses the gains the person set.
 */
enum class EqPreset(val gains: List<Int>) {
    Flat(listOf(0, 0, 0, 0, 0)),
    BassBoost(listOf(6, 4, 0, 0, 0)),
    Vocal(listOf(-2, 0, 4, 3, 0)),
    Rock(listOf(4, 2, -1, 2, 4)),
    Pop(listOf(-1, 2, 4, 2, -1)),
    Treble(listOf(0, 0, 0, 3, 6)),
    Custom(listOf(0, 0, 0, 0, 0)),
}

/** Centre frequencies (Hz) of the 5 equalizer bands shown in Settings. */
val EQ_BANDS_HZ = listOf(60, 230, 910, 3_600, 14_000)

/** Sound settings, applied by the player. */
data class AudioSettings(
    val eqEnabled: Boolean = false,
    val eqPreset: EqPreset = EqPreset.Flat,
    /** Gains (dB, -10..10) of the 5 bands for [EqPreset.Custom]. */
    val eqCustom: List<Int> = listOf(0, 0, 0, 0, 0),
    /** Evens out loud and quiet songs. */
    val volumeLeveling: Boolean = false,
    /** Skips silent parts (useful for podcasts). */
    val skipSilence: Boolean = false,
    /** Playback speed for podcast episodes (music always plays at 1x). */
    val podcastSpeed: Float = 1f,
) {
    /** The gains in use. */
    val gains: List<Int> get() = if (eqPreset == EqPreset.Custom) eqCustom else eqPreset.gains
}

enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
    Tagalog("fil"),
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val accent: AccentColor = AccentColor.Green,
    val dataSaver: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val librarySort: LibrarySort = LibrarySort.Recent,
    val libraryGrid: Boolean = false,
    val transition: TransitionMode = TransitionMode.Off,
    val audio: AudioSettings = AudioSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    /**
     * The free, no-account app is in use. False only on the very first launch, when the Welcome
     * screen is shown; its "Get started" button turns this on.
     */
    val guestMode: Boolean = false,
)

/** The file type songs are saved as. */
enum class DownloadFormat {
    /** As YouTube sends it (.opus or .m4a): best quality, no conversion, fastest. */
    Automatic,
    /** MP3 (192 kbps): plays everywhere, including old players and cars. */
    Mp3,
    /** M4A (AAC, 256 kbps): plays everywhere, smaller than MP3 for the same quality. */
    M4a,
}

data class DownloadSettings(
    val format: DownloadFormat = DownloadFormat.Automatic,
    /** Songs downloaded at the same time (1..[MAX_PARALLEL]); the rest wait in line. */
    val maxParallel: Int = 3,
) {
    companion object {
        const val MAX_PARALLEL = 10
    }
}
