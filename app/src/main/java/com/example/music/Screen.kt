package com.example.music

sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data object Personalization : Screen()
    data object Library : Screen()
    data object Data : Screen()
    data object Playback : Screen()
    data object AudioOutput : Screen()
    data object LyricsAndDevices : Screen()
    data object LyricsSettings : Screen()
    data object AudioCodecs : Screen()
    data object AboutSupport : Screen()
    data object Equalizer : Screen()
    data object LocalMusic : Screen()
    data object PlayerView : Screen()

    fun backDestination(): Screen? = when (this) {
        Home -> null
        LocalMusic -> Home
        PlayerView -> Home
        Equalizer -> Home
        Settings -> Home
        Library, Data, Playback, AudioOutput, Personalization, LyricsAndDevices, LyricsSettings, AudioCodecs,
        AboutSupport -> Settings
    }
}
