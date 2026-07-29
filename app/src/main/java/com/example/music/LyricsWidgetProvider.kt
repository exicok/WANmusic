package com.example.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * 桌面歌词部件：展示封面、当前曲目、歌词与播放控制。
 */
class LyricsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.example.music.WIDGET_TOGGLE_PLAY"
        const val ACTION_NEXT = "com.example.music.WIDGET_NEXT"
        const val ACTION_PREV = "com.example.music.WIDGET_PREV"

        private const val ART_MAX_PX = 192

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, LyricsWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            LyricsWidgetProvider().onUpdate(context, manager, ids)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: manager.getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))
                for (widgetId in ids) {
                    updateWidget(context, manager, widgetId)
                }
            }
            ACTION_TOGGLE_PLAY, ACTION_NEXT, ACTION_PREV -> {
                val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                    action = intent.action
                }
                runCatching { context.startService(serviceIntent) }
                updateAllWidgets(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_lyrics)

        val title = LyricsStateHolder.currentSongTitle()
        val artist = LyricsStateHolder.currentSongArtist()
        val lyric = LyricsStateHolder.currentLyricLine()
        val playing = LyricsStateHolder.isPlaying

        val subtitle = when {
            title.isEmpty() -> context.getString(R.string.widget_idle_title)
            artist.isNotEmpty() -> "$title · $artist"
            else -> title
        }

        views.setTextViewText(R.id.widget_song_title, subtitle)
        views.setTextViewText(
            R.id.widget_lyric_text,
            lyric.ifEmpty {
                if (title.isEmpty()) {
                    context.getString(R.string.widget_idle_lyric)
                } else {
                    context.getString(R.string.widget_no_lyric)
                }
            }
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            if (playing) "暂停" else "播放"
        )

        bindArtwork(context, views)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPending = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, launchPending)
        views.setOnClickPendingIntent(R.id.widget_lyric_text, launchPending)
        views.setOnClickPendingIntent(R.id.widget_song_title, launchPending)
        views.setOnClickPendingIntent(R.id.widget_artwork, launchPending)

        views.setOnClickPendingIntent(
            R.id.widget_prev,
            controlPendingIntent(context, ACTION_PREV, 1)
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            controlPendingIntent(context, ACTION_TOGGLE_PLAY, 2)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            controlPendingIntent(context, ACTION_NEXT, 3)
        )
        views.setViewVisibility(R.id.widget_controls, View.VISIBLE)

        manager.updateAppWidget(widgetId, views)
    }

    private fun bindArtwork(context: Context, views: RemoteViews) {
        val song = LyricsStateHolder.currentSong
        val bitmap = song?.let { ScanRecordsManager.loadArtworkBitmap(context, it, ART_MAX_PX) }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_artwork, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_widget_art_placeholder)
        }
    }

    private fun controlPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, LyricsWidgetProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
