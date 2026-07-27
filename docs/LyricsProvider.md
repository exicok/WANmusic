# WANmusic Lyrics Provider

WANmusic exposes synchronized playback and lyric information through both broadcasts and a read-only `ContentProvider`.

## Broadcast

Action:

```text
com.example.music.action.LYRICS_STATE_CHANGED
```

The broadcast is sent approximately every 500 ms while playback advances. Extras include:

- `title`, `artist`, `song_uri`
- `position_ms`, `duration_ms`, `is_playing`
- `line`, `line_index`, `line_begin_ms`, `line_end_ms`
- `line_progress`, `played_code_points`, `total_code_points`
- `state_uri`, `lyrics_uri`

## Current state

```text
content://com.example.music.lyrics/state
```

The query returns one row containing the current song, playback clock, current lyric line, and per-character progress.

## Full lyrics

```text
content://com.example.music.lyrics/lyrics
```

The query returns the complete synchronized lyric timeline using these columns:

```text
timestamp_ms
text
```

## Android query example

```kotlin
val uri = Uri.parse("content://com.example.music.lyrics/state")
contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
        val line = cursor.getString(cursor.getColumnIndexOrThrow("line"))
        val position = cursor.getLong(cursor.getColumnIndexOrThrow("position_ms"))
    }
}
```

The user can disable external access from `播放设置 > 通用歌词提供服务`.
