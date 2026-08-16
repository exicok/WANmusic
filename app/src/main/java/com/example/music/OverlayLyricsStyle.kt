package com.example.music

import android.content.Context
import android.graphics.Color

data class OverlayLyricsStyle(
    val backgroundEnabled: Boolean,
    val backgroundColor: Int,
    val textColor: Int,
    val textSizeSp: Float,
    val fontFamily: String
) {
    companion object {
        const val KEY_BACKGROUND_ENABLED = "overlay_lyrics_background_enabled"
        const val KEY_BACKGROUND_COLOR = "overlay_lyrics_background_color"
        const val KEY_TEXT_COLOR = "overlay_lyrics_text_color"
        const val KEY_TEXT_SIZE = "overlay_lyrics_text_size"
        const val KEY_FONT_FAMILY = "overlay_lyrics_font_family"

        fun load(context: Context): OverlayLyricsStyle {
            val prefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            return OverlayLyricsStyle(
                backgroundEnabled = prefs.getBoolean(KEY_BACKGROUND_ENABLED, true),
                backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.argb(0xCC, 0, 0, 0)),
                textColor = prefs.getInt(KEY_TEXT_COLOR, Color.WHITE),
                textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 18f).coerceIn(12f, 36f),
                fontFamily = prefs.getString(KEY_FONT_FAMILY, "sans") ?: "sans"
            )
        }
    }
}
