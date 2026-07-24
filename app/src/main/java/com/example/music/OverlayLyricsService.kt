package com.example.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 悬浮歌词服务：在其它应用上层显示可拖动、支持单字进度的当前歌词。
 */
class OverlayLyricsService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_lyrics_channel"
        private const val NOTIFICATION_ID = 7701
        private const val ACTION_STOP = "com.example.music.OVERLAY_STOP"
        private const val PREFS = "music_prefs"
        private const val KEY_ENABLED = "overlay_lyrics_enabled"
        private const val KEY_POS_X = "overlay_lyrics_x"
        private const val KEY_POS_Y = "overlay_lyrics_y"

        private val COLOR_PLAYED = Color.WHITE
        private val COLOR_PENDING = Color.argb(0x59, 0xFF, 0xFF, 0xFF) // ~35% white
        private val COLOR_TITLE = Color.argb(0xB0, 0xFF, 0xFF, 0xFF)

        fun start(context: Context) {
            val intent = Intent(context, OverlayLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayLyricsService::class.java))
        }

        fun isRunning(): Boolean = LyricsStateHolder._overlayRunning

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun startIfEnabled(context: Context) {
            if (isEnabled(context) && Settings.canDrawOverlays(context) && !isRunning()) {
                start(context)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastSignature: String = ""
    private var mediaController: MediaController? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        LyricsStateHolder._overlayRunning = true
        connectMediaController()

        if (!Settings.canDrawOverlays(this)) {
            setEnabled(this, false)
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_lyrics, null)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_POS_X, 48)
            y = prefs.getInt(KEY_POS_Y, 220)
        }
        layoutParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        overlayView?.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        prefs.edit()
                            .putInt(KEY_POS_X, lp.x)
                            .putInt(KEY_POS_Y, lp.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("OverlayLyrics", "Failed to add overlay view", e)
            stopSelf()
            return
        }

        startUpdateLoop()
    }

    private fun connectMediaController() {
        runCatching {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            future.addListener({
                runCatching {
                    mediaController = future.get()
                }
            }, MoreExecutors.directExecutor())
        }.onFailure {
            android.util.Log.w("OverlayLyrics", "MediaController connect failed", it)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startUpdateLoop() {
        updateJob = scope.launch {
            while (isActive) {
                updateOverlay()
                delay(40)
            }
        }
    }

    private fun readPlaybackPosition(): Long {
        val controller = mediaController
        if (controller != null) {
            val pos = controller.currentPosition.coerceAtLeast(0L)
            val playing = controller.isPlaying
            val dur = controller.duration.takeIf { it > 0L }
            LyricsStateHolder.updatePlaybackClock(pos, playing, dur)
            return pos
        }
        return LyricsStateHolder.effectivePosition()
    }

    private fun updateOverlay() {
        val songTitleView = overlayView?.findViewById<TextView>(R.id.overlay_song_title)
        val lyricTextView = overlayView?.findViewById<TextView>(R.id.overlay_lyric_text)

        val position = readPlaybackPosition()
        val progress = LyricsStateHolder.resolveLineProgress(position)
        val title = LyricsStateHolder.currentSongTitle()
        val artist = LyricsStateHolder.currentSongArtist()

        val titleText = when {
            title.isEmpty() -> getString(R.string.overlay_idle_title)
            artist.isNotEmpty() -> "$title - $artist"
            else -> title
        }

        val signature = if (progress == null) {
            "idle|$titleText|${position / 80}"
        } else {
            "${progress.lineIndex}|${progress.playedCodePoints}|${progress.line}|$titleText"
        }
        if (signature == lastSignature) return
        lastSignature = signature

        songTitleView?.setTextColor(COLOR_TITLE)
        songTitleView?.text = titleText

        if (progress == null || progress.line.isEmpty()) {
            lyricTextView?.setTextColor(COLOR_PLAYED)
            lyricTextView?.text = if (title.isEmpty()) {
                getString(R.string.overlay_idle_lyric)
            } else {
                "···"
            }
            return
        }

        lyricTextView?.text = LyricsStateHolder.buildKaraokeSpannable(
            playedColor = COLOR_PLAYED,
            pendingColor = COLOR_PENDING,
            position = position
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        updateJob?.cancel()
        scope.cancel()
        runCatching { mediaController?.release() }
        mediaController = null
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        windowManager = null
        layoutParams = null
        LyricsStateHolder._overlayRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayLyricsService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.overlay_action_close), stopPending).build()
            )
            .setOngoing(true)
            .build()
    }
}
