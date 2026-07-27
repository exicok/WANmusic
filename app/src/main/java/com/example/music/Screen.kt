package com.example.music

sealed class Screen {
    data object Settings : Screen()
    data object Library : Screen()
    data object WebDav : Screen()
    data object Data : Screen()
    data object Playback : Screen()
    data object AudioCodecs : Screen()
    data object AboutSupport : Screen()
    data object Equalizer : Screen()
    data object LocalMusic : Screen()
    data object PlayerView : Screen()
}
