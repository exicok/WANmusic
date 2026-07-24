package com.example.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * 跨组件共享的播放与歌词状态。
 * 供桌面歌词部件、悬浮歌词服务读取。
 */
object LyricsStateHolder {
    data class LineProgress(
        val title: String,
        val artist: String,
        val line: String,
        val lineIndex: Int,
        val lineBegin: Long,
        val lineEnd: Long,
        /** 0f..1f 当前句内进度 */
        val progress: Float,
        /** 已播放到的 code point 数量 */
        val playedCodePoints: Int,
        val totalCodePoints: Int
    )

    var currentSong: Song? = null
    var lyrics: List<Pair<Long, String>> = emptyList()
    var currentPosition: Long = 0L
    var duration: Long = 0L
    var isPlaying: Boolean = false
    var musicDirectories: List<Uri> = emptyList()
    var _overlayRunning: Boolean = false

    /** 最近一次由主界面同步 position 时的开机时间戳 */
    private var lastSyncElapsedMs: Long = 0L

    private var lastPublishedLine: String = ""
    private var lastPublishedTitle: String = ""
    private var lastPublishedArtist: String = ""
    private var lastPublishedPlaying: Boolean? = null

    fun currentLyricLine(): String = resolveLineProgress()?.line.orEmpty()

    fun currentSongTitle(): String = currentSong?.title.orEmpty()
    fun currentSongArtist(): String = currentSong?.artist.orEmpty()

    /**
     * 有效播放进度：前台由主界面驱动；后台若仍在播放则按墙上时钟外推。
     */
    fun effectivePosition(overridePosition: Long? = null): Long {
        if (overridePosition != null) return overridePosition.coerceAtLeast(0L)
        if (!isPlaying || lastSyncElapsedMs <= 0L) return currentPosition.coerceAtLeast(0L)
        val elapsed = SystemClock.elapsedRealtime() - lastSyncElapsedMs
        val projected = currentPosition + elapsed.coerceAtLeast(0L)
        return if (duration > 0L) projected.coerceIn(0L, duration) else projected.coerceAtLeast(0L)
    }

    fun resolveLineProgress(position: Long = effectivePosition()): LineProgress? {
        if (lyrics.isEmpty()) return null
        val sorted = lyrics
        val idx = sorted.indexOfLast { it.first <= position }
        if (idx < 0) return null
        val (begin, text) = sorted[idx]
        val next = sorted.asSequence()
            .drop(idx + 1)
            .map { it.first }
            .firstOrNull { it > begin }
        val end = next
            ?: duration.takeIf { it > begin }
            ?: (begin + 5_000L)
        val safeEnd = end.coerceAtLeast(begin + 1L)
        val progress = ((position - begin).toFloat() / (safeEnd - begin).toFloat()).coerceIn(0f, 1f)
        val totalCodePoints = if (text.isEmpty()) 0 else text.codePointCount(0, text.length)
        val playedCodePoints = if (totalCodePoints == 0) {
            0
        } else {
            (totalCodePoints * progress).toInt().coerceIn(0, totalCodePoints)
        }
        return LineProgress(
            title = currentSongTitle(),
            artist = currentSongArtist(),
            line = text,
            lineIndex = idx,
            lineBegin = begin,
            lineEnd = safeEnd,
            progress = progress,
            playedCodePoints = playedCodePoints,
            totalCodePoints = totalCodePoints
        )
    }

    /**
     * 构建单字进度着色文本：已唱亮色，未唱半透明。
     */
    fun buildKaraokeSpannable(
        playedColor: Int,
        pendingColor: Int,
        position: Long = effectivePosition()
    ): CharSequence {
        val info = resolveLineProgress(position)
        val text = info?.line.orEmpty()
        if (text.isEmpty()) return text
        val playedEnd = if (info == null || info.totalCodePoints == 0) {
            0
        } else {
            text.offsetByCodePoints(0, info.playedCodePoints.coerceIn(0, info.totalCodePoints))
        }
        val spannable = SpannableString(text)
        if (playedEnd > 0) {
            spannable.setSpan(
                ForegroundColorSpan(playedColor),
                0,
                playedEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (playedEnd < text.length) {
            spannable.setSpan(
                ForegroundColorSpan(pendingColor),
                playedEnd,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /**
     * 由主界面同步播放状态；当展示内容变化时刷新桌面部件。
     */
    fun publish(
        context: Context,
        song: Song?,
        lyrics: List<Pair<Long, String>>,
        position: Long,
        duration: Long,
        playing: Boolean
    ) {
        currentSong = song
        this.lyrics = lyrics
        currentPosition = position
        this.duration = duration
        isPlaying = playing
        lastSyncElapsedMs = SystemClock.elapsedRealtime()

        val line = currentLyricLine()
        val title = currentSongTitle()
        val artist = currentSongArtist()
        val changed = line != lastPublishedLine ||
            title != lastPublishedTitle ||
            artist != lastPublishedArtist ||
            lastPublishedPlaying != playing

        if (changed) {
            lastPublishedLine = line
            lastPublishedTitle = title
            lastPublishedArtist = artist
            lastPublishedPlaying = playing
            LyricsWidgetProvider.updateAllWidgets(context.applicationContext)
        }
    }

    /** 悬浮歌词/部件可直接回写播放器真实进度，减少外推误差。 */
    fun updatePlaybackClock(position: Long, playing: Boolean, duration: Long? = null) {
        currentPosition = position.coerceAtLeast(0L)
        isPlaying = playing
        if (duration != null && duration > 0L) this.duration = duration
        lastSyncElapsedMs = SystemClock.elapsedRealtime()
    }

    fun clear(context: Context? = null) {
        currentSong = null
        lyrics = emptyList()
        currentPosition = 0L
        duration = 0L
        isPlaying = false
        lastSyncElapsedMs = 0L
        lastPublishedLine = ""
        lastPublishedTitle = ""
        lastPublishedArtist = ""
        lastPublishedPlaying = null
        context?.applicationContext?.let { LyricsWidgetProvider.updateAllWidgets(it) }
    }
}
