package com.example.music

import android.content.Context

/** 仅通知 ContentProvider 观察者刷新，不发送系统广播。 */
object LyricsProviderNotifier {
    private var lastSignature = ""
    private var lastSongUri = ""

    @Synchronized
    fun notifyChanged(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val enabled = appContext
            .getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            .getBoolean("lyrics_provider_enabled", true)
        if (!enabled && !force) return

        val snapshot = LyricsStateHolder.snapshot()
        val line = snapshot.lineProgress
        val signature = listOf(
            snapshot.songUri,
            snapshot.isPlaying,
            snapshot.position / 500L,
            line?.lineIndex ?: -1,
            line?.playedCodePoints ?: 0
        ).joinToString("|")
        if (!force && signature == lastSignature) return

        appContext.contentResolver.notifyChange(LyricsProviderContract.STATE_URI, null)
        if (force || snapshot.songUri != lastSongUri) {
            appContext.contentResolver.notifyChange(LyricsProviderContract.LYRICS_URI, null)
            lastSongUri = snapshot.songUri
        }
        lastSignature = signature
    }
}
