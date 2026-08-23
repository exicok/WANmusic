package com.example.music

import android.net.Uri

object LyricsProviderContract {
    const val AUTHORITY = "com.example.music.lyrics"
    const val VERSION = 6

    const val PATH_STATE = "state"
    const val PATH_LYRICS = "lyrics"

    val STATE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")
    val LYRICS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_LYRICS")

    object Columns {
        const val DATA_VERSION = "data_version"
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
        const val NEXT_LINE = "next_line"
        const val TRANSLATION = "translation"
        const val TIMESTAMP_MS = "timestamp_ms"
        const val TEXT = "text"
    }

}
