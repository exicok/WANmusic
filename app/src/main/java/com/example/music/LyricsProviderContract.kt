package com.example.music

import android.net.Uri

object LyricsProviderContract {
    const val AUTHORITY = "com.example.music.lyrics"
    const val ACTION_STATE_CHANGED = "com.example.music.action.LYRICS_STATE_CHANGED"

    const val PATH_STATE = "state"
    const val PATH_LYRICS = "lyrics"

    val STATE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")
    val LYRICS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_LYRICS")

    object Columns {
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val SONG_URI = "song_uri"
        const val POSITION_MS = "position_ms"
        const val DURATION_MS = "duration_ms"
        const val IS_PLAYING = "is_playing"
        const val LINE = "line"
        const val LINE_INDEX = "line_index"
        const val LINE_BEGIN_MS = "line_begin_ms"
        const val LINE_END_MS = "line_end_ms"
        const val LINE_PROGRESS = "line_progress"
        const val PLAYED_CODE_POINTS = "played_code_points"
        const val TOTAL_CODE_POINTS = "total_code_points"
        const val TIMESTAMP_MS = "timestamp_ms"
        const val TEXT = "text"
    }

    object Extras {
        const val TITLE = Columns.TITLE
        const val ARTIST = Columns.ARTIST
        const val SONG_URI = Columns.SONG_URI
        const val POSITION_MS = Columns.POSITION_MS
        const val DURATION_MS = Columns.DURATION_MS
        const val IS_PLAYING = Columns.IS_PLAYING
        const val LINE = Columns.LINE
        const val LINE_INDEX = Columns.LINE_INDEX
        const val LINE_BEGIN_MS = Columns.LINE_BEGIN_MS
        const val LINE_END_MS = Columns.LINE_END_MS
        const val LINE_PROGRESS = Columns.LINE_PROGRESS
        const val PLAYED_CODE_POINTS = Columns.PLAYED_CODE_POINTS
        const val TOTAL_CODE_POINTS = Columns.TOTAL_CODE_POINTS
        const val STATE_URI = "state_uri"
        const val LYRICS_URI = "lyrics_uri"
    }
}
