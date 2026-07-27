package com.example.music

import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var boundEqSessionId: Int = 0

    private var pendingGains = IntArray(EQUALIZER_FREQUENCIES.size)
    private var eqEnabled = true
    private var preampDb = 0
    private var bassBoostStrength = 0
    private var virtualizerStrength = 0
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastCarLyricLine = ""
    private var lastCarLyricsRevision = -1L

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_APPLY_EQUALIZER = "com.example.music.APPLY_EQUALIZER"
        const val ACTION_REFRESH_WEBDAV_AUTH = "com.example.music.REFRESH_WEBDAV_AUTH"
        const val EXTRA_GAINS = "gains"
        const val EXTRA_EQ_ENABLED = "eq_enabled"
        const val EXTRA_PREAMP = "preamp"
        const val EXTRA_BASS_BOOST = "bass_boost"
        const val EXTRA_VIRTUALIZER = "virtualizer"
        /** UI 10 段中心频率（Hz） */
        val EQUALIZER_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    }

    override fun onCreate() {
        super.onCreate()
        httpFactory = DefaultHttpDataSource.Factory()
        updateWebDavHeaders()
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        // 使用标准渲染管线，保证 Equalizer 可挂到播放会话
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // 不申请独占音频焦点，允许与导航、车机及其它音乐应用同时播放。
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val prefs = getSharedPreferences("music_prefs", MODE_PRIVATE)
        loadFxFromPrefs(prefs)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioSessionHolder.sessionId = audioSessionId
                attachEffects(audioSessionId, forceRebind = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    attachEffects(player.audioSessionId, forceRebind = false)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                LyricsStateHolder.isPlaying = isPlaying
                LyricsWidgetProvider.updateAllWidgets(this@PlaybackService)
                // 开始播放时再确保效果挂上（部分机型 session 就绪偏晚）
                if (isPlaying) {
                    attachEffects(player.audioSessionId, forceRebind = false)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        AudioSessionHolder.sessionId = player.audioSessionId
        attachEffects(player.audioSessionId, forceRebind = true)
        startLyricsBridge(player)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player as? ExoPlayer
        val sessionId = player?.audioSessionId ?: AudioSessionHolder.sessionId

        when (intent?.action) {
            ACTION_REFRESH_WEBDAV_AUTH -> updateWebDavHeaders()
            ACTION_APPLY_EQUALIZER -> {
                intent.getIntArrayExtra(EXTRA_GAINS)?.let { pendingGains = it }
                if (intent.hasExtra(EXTRA_EQ_ENABLED)) {
                    eqEnabled = intent.getBooleanExtra(EXTRA_EQ_ENABLED, true)
                }
                if (intent.hasExtra(EXTRA_PREAMP)) {
                    preampDb = intent.getIntExtra(EXTRA_PREAMP, 0).coerceIn(-6, 6)
                }
                if (intent.hasExtra(EXTRA_BASS_BOOST)) {
                    bassBoostStrength = intent.getIntExtra(EXTRA_BASS_BOOST, 0).coerceIn(0, 1000)
                }
                if (intent.hasExtra(EXTRA_VIRTUALIZER)) {
                    virtualizerStrength = intent.getIntExtra(EXTRA_VIRTUALIZER, 0).coerceIn(0, 1000)
                }
                persistFxPrefs()
                attachEffects(sessionId, forceRebind = equalizer == null)
                applyAllEffects()
            }
            LyricsWidgetProvider.ACTION_TOGGLE_PLAY -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
            }
            LyricsWidgetProvider.ACTION_NEXT -> player?.seekToNextMediaItem()
            LyricsWidgetProvider.ACTION_PREV -> player?.seekToPreviousMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateWebDavHeaders() {
        val auth = WebDavRepository.authorization(WebDavRepository.load(this))
        httpFactory.setDefaultRequestProperties(if (auth == null) emptyMap() else mapOf("Authorization" to auth))
    }

    /**
     * 将当前歌词行写入 MediaSession 元数据。Android Auto/AAOS/蓝牙车机可在媒体卡片上显示。
     * 服务后台播放时继续刷新，不依赖播放页保持前台。
     */
    private fun startLyricsBridge(player: ExoPlayer) {
        serviceScope.launch {
            while (isActive) {
                LyricsStateHolder.updatePlaybackClock(
                    position = player.currentPosition.coerceAtLeast(0L),
                    playing = player.isPlaying,
                    duration = player.duration.takeIf { it > 0L }
                )
                LyricsInteropPublisher.publish(this@PlaybackService)
                if (getSharedPreferences("music_prefs", MODE_PRIVATE)
                        .getBoolean("car_lyrics_enabled", true)
                ) {
                    val line = LyricsStateHolder.currentLyricLine()
                    val revision = LyricsStateHolder.carLyricsRevision()
                    val currentItem = player.currentMediaItem
                    if ((line != lastCarLyricLine || revision != lastCarLyricsRevision) &&
                        currentItem != null &&
                        player.currentMediaItemIndex >= 0
                    ) {
                        val currentMetadata = player.mediaMetadata
                        val title = LyricsStateHolder.currentSongTitle()
                            .ifBlank { currentMetadata.title?.toString().orEmpty() }
                        val artist = LyricsStateHolder.currentSongArtist()
                            .ifBlank { currentMetadata.artist?.toString().orEmpty() }
                        val carMetadata = currentMetadata.buildUpon()
                                .setTitle(title)
                                // AVRCP 老车机通常只读取标题/歌手两行，将歌词放在第二行。
                                .setArtist(line.ifBlank { artist })
                                .setAlbumArtist(artist)
                                .setSubtitle(line.ifBlank { artist })
                                .setDescription(line.takeIf { it.isNotBlank() })
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        player.replaceMediaItem(
                            player.currentMediaItemIndex,
                            currentItem.buildUpon()
                                .setMediaMetadata(carMetadata)
                                .build()
                        )
                        lastCarLyricLine = line
                        lastCarLyricsRevision = revision
                    }
                } else if (lastCarLyricLine.isNotEmpty()) {
                    val currentItem = player.currentMediaItem
                    if (currentItem != null && player.currentMediaItemIndex >= 0) {
                        val metadata = player.mediaMetadata
                        val artist = LyricsStateHolder.currentSongArtist()
                            .ifBlank { metadata.artist?.toString().orEmpty() }
                        player.replaceMediaItem(
                            player.currentMediaItemIndex,
                            currentItem.buildUpon()
                                .setMediaMetadata(
                                    metadata.buildUpon()
                                        .setArtist(artist)
                                        .setAlbumArtist(artist)
                                        .setSubtitle(artist)
                                        .setDescription(null)
                                        .build()
                                )
                                .build()
                        )
                    }
                    lastCarLyricLine = ""
                }
                delay(250L)
            }
        }
    }

    private fun loadFxFromPrefs(prefs: android.content.SharedPreferences) {
        pendingGains = loadGains(prefs)
        eqEnabled = prefs.getBoolean("equalizer_enabled", true)
        preampDb = prefs.getInt("equalizer_preamp", 0).coerceIn(-6, 6)
        bassBoostStrength = prefs.getInt("bass_boost", 0).coerceIn(0, 1000)
        virtualizerStrength = prefs.getInt("virtualizer", 0).coerceIn(0, 1000)
    }

    private fun persistFxPrefs() {
        getSharedPreferences("music_prefs", MODE_PRIVATE)
            .edit()
            .putString("equalizer_gains", pendingGains.joinToString(","))
            .putBoolean("equalizer_enabled", eqEnabled)
            .putInt("equalizer_preamp", preampDb)
            .putInt("bass_boost", bassBoostStrength)
            .putInt("virtualizer", virtualizerStrength)
            .apply()
    }

    private fun attachEffects(audioSessionId: Int, forceRebind: Boolean) {
        if (audioSessionId > 0) {
            AudioSessionHolder.sessionId = audioSessionId
        }
        if (audioSessionId <= 0) {
            Log.w(TAG, "Skip FX attach: invalid sessionId=$audioSessionId")
            return
        }

        // 同一 session 且实例可用：只重应用参数
        if (!forceRebind &&
            equalizer != null &&
            boundEqSessionId == audioSessionId &&
            runCatching { equalizer!!.hasControl() }.getOrDefault(false)
        ) {
            applyAllEffects()
            return
        }

        releaseEffects()
        runCatching {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            boundEqSessionId = audioSessionId

            runCatching {
                val bb = BassBoost(0, audioSessionId)
                bassBoost = bb
            }.onFailure { Log.w(TAG, "BassBoost unavailable", it) }

            runCatching {
                val vz = Virtualizer(0, audioSessionId)
                virtualizer = vz
            }.onFailure { Log.w(TAG, "Virtualizer unavailable", it) }

            applyAllEffects()
            Log.i(
                TAG,
                "FX attached session=$audioSessionId bands=${eq.numberOfBands} " +
                    "range=${eq.bandLevelRange.joinToString()} " +
                    "enabled=$eqEnabled preamp=$preampDb bass=$bassBoostStrength virt=$virtualizerStrength " +
                    "gains=${pendingGains.joinToString()}"
            )
        }.onFailure {
            Log.e(TAG, "Failed to attach equalizer on session=$audioSessionId", it)
            releaseEffects()
        }
    }

    private fun applyAllEffects() {
        val eq = equalizer ?: return
        runCatching {
            if (eqEnabled) {
                eq.enabled = true
                applyGains(eq, pendingGains, preampDb)
            } else {
                // 关闭时复位为平直并禁用，避免残留着色
                applyGains(eq, IntArray(EQUALIZER_FREQUENCIES.size), 0)
                eq.enabled = false
            }
        }.onFailure { Log.w(TAG, "Apply EQ failed", it) }

        runCatching {
            val bb = bassBoost
            if (bb != null) {
                val strength = if (eqEnabled) bassBoostStrength else 0
                if (strength > 0) {
                    bb.setStrength(strength.toShort())
                    bb.enabled = true
                } else {
                    bb.setStrength(0)
                    bb.enabled = false
                }
            }
        }.onFailure { Log.w(TAG, "Apply BassBoost failed", it) }

        runCatching {
            val vz = virtualizer
            if (vz != null) {
                val strength = if (eqEnabled) virtualizerStrength else 0
                if (strength > 0) {
                    vz.setStrength(strength.toShort())
                    vz.enabled = true
                } else {
                    vz.setStrength(0)
                    vz.enabled = false
                }
            }
        }.onFailure { Log.w(TAG, "Apply Virtualizer failed", it) }
    }

    private fun releaseEffects() {
        runCatching {
            equalizer?.enabled = false
            equalizer?.release()
        }
        runCatching {
            bassBoost?.enabled = false
            bassBoost?.release()
        }
        runCatching {
            virtualizer?.enabled = false
            virtualizer?.release()
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        boundEqSessionId = 0
    }

    /**
     * 将 UI 的 10 段 dB 增益 + 前置增益映射到设备实际频段。
     * Android Equalizer 使用 millibel（1dB = 100mB）。
     */
    private fun applyGains(effect: Equalizer, gains: IntArray, preamp: Int) {
        val bandCount = effect.numberOfBands.toInt()
        if (bandCount <= 0) return
        val range = effect.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()

        // 判断 getCenterFreq 返回的是 mHz 还是 Hz（band0 中心通常远小于 1kHz）
        val refRaw = runCatching { effect.getCenterFreq(0) }.getOrDefault(0)
        val rawIsMilliHz = refRaw > 1_000

        for (band in 0 until bandCount) {
            val rawCenter = runCatching { effect.getCenterFreq(band.toShort()) }.getOrDefault(0)
            val centerHz = if (rawIsMilliHz) {
                (rawCenter / 1_000).coerceAtLeast(1)
            } else {
                rawCenter.coerceAtLeast(1)
            }

            val gainDb = gainForFrequencyHz(centerHz, gains) + preamp
            val levelMb = (gainDb * 100).roundToInt().coerceIn(minLevel, maxLevel).toShort()
            runCatching {
                effect.setBandLevel(band.toShort(), levelMb)
            }.onFailure {
                Log.w(TAG, "setBandLevel failed band=$band", it)
            }
        }
        runCatching { effect.enabled = true }
    }

    /** 在对数频率轴上，用邻近 UI 频点插值得到该中心频率的增益（dB） */
    private fun gainForFrequencyHz(centerHz: Int, gains: IntArray): Float {
        val freqs = EQUALIZER_FREQUENCIES
        if (gains.isEmpty()) return 0f
        if (centerHz <= freqs.first()) return gains.first().toFloat()
        if (centerHz >= freqs.last()) return gains[minOf(gains.lastIndex, freqs.lastIndex)].toFloat()

        var hi = 1
        while (hi < freqs.size && freqs[hi] < centerHz) hi++
        val lo = (hi - 1).coerceAtLeast(0)
        hi = hi.coerceAtMost(freqs.lastIndex)

        val f0 = freqs[lo].toFloat().coerceAtLeast(1f)
        val f1 = freqs[hi].toFloat().coerceAtLeast(f0 + 1f)
        val g0 = gains.getOrElse(lo) { 0 }.toFloat()
        val g1 = gains.getOrElse(hi) { gains.getOrElse(lo) { 0 } }.toFloat()
        if (lo == hi) return g0

        // 对数插值，更符合听感
        val t = ((ln(centerHz.toFloat()) - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
        return g0 + (g1 - g0) * t
    }

    private fun loadGains(prefs: android.content.SharedPreferences = getSharedPreferences("music_prefs", MODE_PRIVATE)): IntArray {
        val saved = prefs
            .getString("equalizer_gains", null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return IntArray(EQUALIZER_FREQUENCIES.size)
        return IntArray(EQUALIZER_FREQUENCIES.size) { saved.getOrElse(it) { 0 }.coerceIn(-15, 15) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == false || player?.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioSessionHolder.sessionId = 0
        serviceScope.cancel()
        releaseEffects()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
