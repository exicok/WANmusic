package com.example.music

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class AudioOutputConfig(
    val gainDb: Float = 0f,
    val resamplingEnabled: Boolean = false,
    val resamplingSampleRateHz: Int = 48_000,
    val fadeEnabled: Boolean = true,
    val fadeDurationMs: Int = 500,
    val audioTrackBufferMultiplier: Float = 1f,
    val audioTrackFloatOutput: Boolean = false,
    val audioTrackPlaybackParams: Boolean = false
) {
    fun normalized(): AudioOutputConfig = copy(
        gainDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
        resamplingSampleRateHz = resamplingSampleRateHz
            .takeIf { it in SUPPORTED_SAMPLE_RATES }
            ?: 48_000,
        fadeDurationMs = fadeDurationMs.coerceIn(MIN_FADE_DURATION_MS, MAX_FADE_DURATION_MS),
        audioTrackBufferMultiplier = audioTrackBufferMultiplier.coerceIn(1f, 4f)
    )

    fun save(preferences: SharedPreferences) {
        val value = normalized()
        preferences.edit {
            putFloat(KEY_GAIN_DB, value.gainDb)
            putBoolean(KEY_RESAMPLING_ENABLED, value.resamplingEnabled)
            putInt(KEY_RESAMPLING_SAMPLE_RATE, value.resamplingSampleRateHz)
            putBoolean(KEY_FADE_ENABLED, value.fadeEnabled)
            putInt(KEY_FADE_DURATION_MS, value.fadeDurationMs)
            putFloat(KEY_AUDIOTRACK_BUFFER_MULTIPLIER, value.audioTrackBufferMultiplier)
            putBoolean(KEY_AUDIOTRACK_FLOAT_OUTPUT, value.audioTrackFloatOutput)
            putBoolean(KEY_AUDIOTRACK_PLAYBACK_PARAMS, value.audioTrackPlaybackParams)
        }
    }

    companion object {
        const val PREFS_NAME = "music_prefs"
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 12f
        const val MIN_FADE_DURATION_MS = 100
        const val MAX_FADE_DURATION_MS = 2_000
        val SUPPORTED_SAMPLE_RATES = intArrayOf(44_100, 48_000, 96_000)

        private const val KEY_GAIN_DB = "audio_output_gain_db"
        private const val KEY_RESAMPLING_ENABLED = "audio_resampling_enabled"
        private const val KEY_RESAMPLING_SAMPLE_RATE = "audio_resampling_sample_rate"
        private const val KEY_FADE_ENABLED = "playback_fade_enabled"
        private const val KEY_FADE_DURATION_MS = "playback_fade_duration_ms"
        private const val KEY_AUDIOTRACK_BUFFER_MULTIPLIER = "audio_track_buffer_multiplier"
        private const val KEY_AUDIOTRACK_FLOAT_OUTPUT = "audio_track_float_output"
        private const val KEY_AUDIOTRACK_PLAYBACK_PARAMS = "audio_track_playback_params"

        fun load(context: Context): AudioOutputConfig = load(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )

        fun load(preferences: SharedPreferences): AudioOutputConfig = AudioOutputConfig(
            gainDb = preferences.getFloat(KEY_GAIN_DB, 0f),
            resamplingEnabled = preferences.getBoolean(KEY_RESAMPLING_ENABLED, false),
            resamplingSampleRateHz = preferences.getInt(KEY_RESAMPLING_SAMPLE_RATE, 48_000),
            fadeEnabled = preferences.getBoolean(KEY_FADE_ENABLED, true),
            fadeDurationMs = preferences.getInt(KEY_FADE_DURATION_MS, 500),
            audioTrackBufferMultiplier = preferences.getFloat(
                KEY_AUDIOTRACK_BUFFER_MULTIPLIER,
                1f
            ),
            audioTrackFloatOutput = preferences.getBoolean(KEY_AUDIOTRACK_FLOAT_OUTPUT, false),
            audioTrackPlaybackParams = preferences.getBoolean(
                KEY_AUDIOTRACK_PLAYBACK_PARAMS,
                false
            )
        ).normalized()
    }
}
