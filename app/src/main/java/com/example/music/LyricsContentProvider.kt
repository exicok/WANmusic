package com.example.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class LyricsContentProvider : ContentProvider() {
    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(LyricsProviderContract.AUTHORITY, LyricsProviderContract.PATH_STATE, MATCH_STATE)
        addURI(LyricsProviderContract.AUTHORITY, LyricsProviderContract.PATH_LYRICS, MATCH_LYRICS)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return when (uriMatcher.match(uri)) {
            MATCH_STATE -> createStateCursor(includeData = isEnabled())
            MATCH_LYRICS -> createLyricsCursor(includeData = isEnabled())
            else -> throw IllegalArgumentException("Unsupported lyrics URI: $uri")
        }
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        MATCH_STATE -> "vnd.android.cursor.item/vnd.${LyricsProviderContract.AUTHORITY}.state"
        MATCH_LYRICS -> "vnd.android.cursor.dir/vnd.${LyricsProviderContract.AUTHORITY}.lyrics"
        else -> throw IllegalArgumentException("Unsupported lyrics URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun createStateCursor(includeData: Boolean): Cursor {
        val columns = LyricsProviderContract.Columns
        val cursor = MatrixCursor(
            arrayOf(
                columns.TITLE,
                columns.ARTIST,
                columns.SONG_URI,
                columns.POSITION_MS,
                columns.DURATION_MS,
                columns.IS_PLAYING,
                columns.LINE,
                columns.LINE_INDEX,
                columns.LINE_BEGIN_MS,
                columns.LINE_END_MS,
                columns.LINE_PROGRESS,
                columns.PLAYED_CODE_POINTS,
                columns.TOTAL_CODE_POINTS
            )
        )
        if (includeData) {
            val snapshot = LyricsStateHolder.snapshot()
            val line = snapshot.lineProgress
            cursor.addRow(
                arrayOf<Any?>(
                    snapshot.title,
                    snapshot.artist,
                    snapshot.songUri,
                    snapshot.position,
                    snapshot.duration,
                    if (snapshot.isPlaying) 1 else 0,
                    line?.line.orEmpty(),
                    line?.lineIndex ?: -1,
                    line?.lineBegin ?: 0L,
                    line?.lineEnd ?: 0L,
                    line?.progress ?: 0f,
                    line?.playedCodePoints ?: 0,
                    line?.totalCodePoints ?: 0
                )
            )
        }
        context?.contentResolver?.let {
            cursor.setNotificationUri(it, LyricsProviderContract.STATE_URI)
        }
        return cursor
    }

    private fun createLyricsCursor(includeData: Boolean): Cursor {
        val columns = LyricsProviderContract.Columns
        val cursor = MatrixCursor(arrayOf(columns.TIMESTAMP_MS, columns.TEXT))
        if (includeData) {
            LyricsStateHolder.snapshot().lyrics.forEach { (timestamp, text) ->
                cursor.addRow(arrayOf<Any?>(timestamp, text))
            }
        }
        context?.contentResolver?.let {
            cursor.setNotificationUri(it, LyricsProviderContract.LYRICS_URI)
        }
        return cursor
    }

    private fun isEnabled(): Boolean = context
        ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        ?.getBoolean(PREF_ENABLED, true)
        ?: true

    private companion object {
        const val MATCH_STATE = 1
        const val MATCH_LYRICS = 2
        const val PREFS_NAME = "music_prefs"
        const val PREF_ENABLED = "lyrics_provider_enabled"
    }
}
