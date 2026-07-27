package com.example.music

import android.content.Context
import android.content.Intent

object LyricsInteropPublisher {
    private var lastSignature = ""
    private var lastSongUri = ""

    @Synchronized
    fun publish(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val enabled = appContext
            .getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            .getBoolean("lyrics_provider_enabled", true)
        if (!enabled) return

        val snapshot = LyricsStateHolder.snapshot()
        val line = snapshot.lineProgress
        val positionBucket = snapshot.position / 500L
        val signature = listOf(
            snapshot.songUri,
            snapshot.isPlaying,
            positionBucket,
            line?.lineIndex ?: -1,
            line?.playedCodePoints ?: 0
        ).joinToString("|")
        if (!force && signature == lastSignature) return

        val extras = LyricsProviderContract.Extras
        val intent = Intent(LyricsProviderContract.ACTION_STATE_CHANGED).apply {
            putExtra(extras.TITLE, snapshot.title)
            putExtra(extras.ARTIST, snapshot.artist)
            putExtra(extras.SONG_URI, snapshot.songUri)
            putExtra(extras.POSITION_MS, snapshot.position)
            putExtra(extras.DURATION_MS, snapshot.duration)
            putExtra(extras.IS_PLAYING, snapshot.isPlaying)
            putExtra(extras.LINE, line?.line.orEmpty())
            putExtra(extras.LINE_INDEX, line?.lineIndex ?: -1)
            putExtra(extras.LINE_BEGIN_MS, line?.lineBegin ?: 0L)
            putExtra(extras.LINE_END_MS, line?.lineEnd ?: 0L)
            putExtra(extras.LINE_PROGRESS, line?.progress ?: 0f)
            putExtra(extras.PLAYED_CODE_POINTS, line?.playedCodePoints ?: 0)
            putExtra(extras.TOTAL_CODE_POINTS, line?.totalCodePoints ?: 0)
            putExtra(extras.STATE_URI, LyricsProviderContract.STATE_URI.toString())
            putExtra(extras.LYRICS_URI, LyricsProviderContract.LYRICS_URI.toString())
        }
        appContext.sendBroadcast(intent)
        appContext.contentResolver.notifyChange(LyricsProviderContract.STATE_URI, null)
        if (snapshot.songUri != lastSongUri) {
            appContext.contentResolver.notifyChange(LyricsProviderContract.LYRICS_URI, null)
            lastSongUri = snapshot.songUri
        }
        lastSignature = signature
    }
}
