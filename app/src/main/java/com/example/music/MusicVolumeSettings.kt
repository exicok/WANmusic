package com.example.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Player-owned volume, independent from the system media stream. */
object MusicVolumeSettings {
    private const val PREFS = "music_prefs"
    private const val KEY_VOLUME = "music_player_volume"
    private const val KEY_MUTED = "music_player_muted"

    var volume by mutableStateOf(1f)
        private set
    var muted by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        volume = prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)
        muted = prefs.getBoolean(KEY_MUTED, false)
    }

    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat(KEY_VOLUME, volume) }
    }

    fun setMuted(context: Context, value: Boolean) {
        muted = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_MUTED, muted) }
    }

    fun effectiveVolume(): Float = if (muted) 0f else volume
}
