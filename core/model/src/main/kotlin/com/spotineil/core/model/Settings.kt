package com.spotineil.core.model

enum class ThemeMode { System, Light, Dark, Amoled }

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

enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Spanish("es"),
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val accent: AccentColor = AccentColor.Green,
    val dataSaver: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val librarySort: LibrarySort = LibrarySort.Recent,
    val libraryGrid: Boolean = false,
    /**
     * The free, no-account app is in use. False only on the very first launch, when the Welcome
     * screen is shown; its "Get started" button turns this on.
     */
    val guestMode: Boolean = false,
)
