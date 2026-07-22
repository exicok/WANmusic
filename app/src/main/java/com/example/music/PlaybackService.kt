package com.example.music

import android.content.Intent
import android.media.audiofx.Equalizer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.util.Log

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    
    private var pendingGains = IntArray(10)
    private var usbDacPassthrough = false
    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_APPLY_EQUALIZER = "com.example.music.APPLY_EQUALIZER"
        const val ACTION_SET_USB_DAC_PASSTHROUGH = "com.example.music.SET_USB_DAC_PASSTHROUGH"
        const val ACTION_REFRESH_WEBDAV_AUTH = "com.example.music.REFRESH_WEBDAV_AUTH"
        const val EXTRA_GAINS = "gains"
        const val EXTRA_USB_DAC_PASSTHROUGH = "usb_dac_passthrough"
        val EQUALIZER_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    }

    override fun onCreate() {
        super.onCreate()
        httpFactory = DefaultHttpDataSource.Factory()
        updateWebDavHeaders()
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        
        val prefs = getSharedPreferences("music_prefs", MODE_PRIVATE)
        pendingGains = loadGains()
        usbDacPassthrough = prefs.getBoolean("usb_dac_passthrough", false)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEffects(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    attachEffects(player.audioSessionId)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        // 首次尝试附加
        attachEffects(player.audioSessionId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player as? ExoPlayer
        val sessionId = player?.audioSessionId ?: 0

        when (intent?.action) {
            ACTION_REFRESH_WEBDAV_AUTH -> updateWebDavHeaders()
            ACTION_SET_USB_DAC_PASSTHROUGH -> {
                usbDacPassthrough = intent.getBooleanExtra(EXTRA_USB_DAC_PASSTHROUGH, false)
                if (usbDacPassthrough) releaseEffects() else attachEffects(sessionId)
            }
            ACTION_APPLY_EQUALIZER -> {
                pendingGains = intent.getIntArrayExtra(EXTRA_GAINS) ?: pendingGains
                if (!usbDacPassthrough) {
                    if (equalizer == null) attachEffects(sessionId)
                    equalizer?.let { applyGains(it, pendingGains) }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateWebDavHeaders() {
        val auth = WebDavRepository.authorization(WebDavRepository.load(this))
        httpFactory.setDefaultRequestProperties(if (auth == null) emptyMap() else mapOf("Authorization" to auth))
    }

    private fun attachEffects(audioSessionId: Int) {
        if (audioSessionId <= 0 || usbDacPassthrough) return
        runCatching {
            // Equalizer (Insert Effect)
            if (equalizer == null || !equalizer!!.hasControl()) {
                equalizer?.release()
                equalizer = Equalizer(1, audioSessionId).apply {
                    enabled = true
                    applyGains(this, pendingGains)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to attach audio effects", it)
        }
    }

    private fun releaseEffects() {
        equalizer?.release(); equalizer = null
    }

    private fun applyGains(effect: Equalizer, gains: IntArray) {
        val bandCount = effect.numberOfBands.toInt()
        val range = effect.bandLevelRange
        for (band in 0 until bandCount) {
            val centerHz = (effect.getBandFreqRange(band.toShort())[0] + effect.getBandFreqRange(band.toShort())[1]) / 2
            val nearest = EQUALIZER_FREQUENCIES.indices.minByOrNull {
                kotlin.math.abs(EQUALIZER_FREQUENCIES[it] - centerHz / 1_000)
            } ?: 0
            val level = (gains.getOrElse(nearest) { 0 } * 100)
                .coerceIn(range[0].toInt(), range[1].toInt())
                .toShort()
            effect.setBandLevel(band.toShort(), level)
        }
    }

    private fun loadGains(): IntArray {
        val saved = getSharedPreferences("music_prefs", MODE_PRIVATE)
            .getString("equalizer_gains", null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return IntArray(10)
        return IntArray(10) { saved.getOrElse(it) { 0 } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == false || player?.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseEffects()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
