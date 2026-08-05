package com.example.music

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
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
            runCatching {
                // PlaybackService already keeps this process in foreground while music is active.
                context.startService(intent)
            }.onFailure {
                android.util.Log.e("OverlayLyrics", "Unable to start overlay service", it)
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
            if (
                isEnabled(context) &&
                Settings.canDrawOverlays(context) &&
                !isRunning()
            ) {
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

    private fun clampOverlayPosition() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val lp = layoutParams ?: return
        val bounds = android.graphics.Rect()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRectSize(bounds)
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }
            ?.let(resources::getDimensionPixelSize)
            ?: 0
        val maxX = (bounds.width() - view.width).coerceAtLeast(0)
        val maxY = (bounds.height() - view.height).coerceAtLeast(statusBarHeight)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(statusBarHeight, maxY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        LyricsStateHolder._overlayRunning = true
        connectMediaController()

        if (!Settings.canDrawOverlays(this)) {
            LyricsStateHolder._overlayRunning = false
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
            x = prefs.getInt(KEY_POS_X, 48).coerceAtLeast(0)
            y = prefs.getInt(KEY_POS_Y, 220).coerceAtLeast(0)
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
                    clampOverlayPosition()
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
            overlayView?.post {
                clampOverlayPosition()
                runCatching { windowManager?.updateViewLayout(overlayView, params) }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayLyrics", "Failed to add overlay view", e)
            LyricsStateHolder._overlayRunning = false
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.post {
            clampOverlayPosition()
            layoutParams?.let { params ->
                runCatching { windowManager?.updateViewLayout(overlayView, params) }
            }
        }
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

}
