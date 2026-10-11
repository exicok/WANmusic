package com.example.music

import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var activePlayer: ExoPlayer? = null
    private var sessionPlayer: FadePlaybackPlayer? = null

    private lateinit var dataSourceFactory: DefaultDataSource.Factory
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyricsBridgeJob: Job? = null
    private var audioOutputRebuildJob: Job? = null
    private var lastCarLyricLine = ""
    private var lastCarLyricsRevision = -1L

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_APPLY_AUDIO_OUTPUT = "com.example.music.APPLY_AUDIO_OUTPUT"
        const val ACTION_SET_AUDIO_GAIN = "com.example.music.SET_AUDIO_GAIN"
        const val ACTION_SET_PLAYER_VOLUME = "com.example.music.SET_PLAYER_VOLUME"
        const val EXTRA_AUDIO_GAIN_DB = "audio_gain_db"
        const val EXTRA_PLAYER_VOLUME = "player_volume"
        const val EXTRA_PLAYER_MUTED = "player_muted"
    }

    override fun onCreate() {
        super.onCreate()
        MusicVolumeSettings.load(this)
        dataSourceFactory = DefaultDataSource.Factory(this)
        val player = createPlayer()
        val forwardingPlayer = createSessionPlayer(player)

        activePlayer = player
        sessionPlayer = forwardingPlayer
        mediaSession = MediaSession.Builder(this, forwardingPlayer).build()
        AudioSessionHolder.sessionId = player.audioSessionId
        startLyricsBridge(player)
    }

    private fun createPlayer(): ExoPlayer {
        val outputConfig = AudioOutputConfig.load(this)
        AudioOutputRuntime.setGainDb(outputConfig.gainDb)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(
            this,
            AudioOutputRenderersFactory(this, outputConfig),
            DefaultMediaSourceFactory(dataSourceFactory)
        )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val generatedSessionId = (getSystemService(AUDIO_SERVICE) as AudioManager)
            .generateAudioSessionId()
        if (generatedSessionId > 0) player.setAudioSessionId(generatedSessionId)
        attachPlayerListeners(player)
        return player
    }

    private fun createSessionPlayer(player: ExoPlayer): FadePlaybackPlayer = FadePlaybackPlayer(
        delegate = player,
        scope = serviceScope,
        configProvider = { AudioOutputConfig.load(this) }
    ).also { it.setVolume(MusicVolumeSettings.effectiveVolume()) }

    private fun attachPlayerListeners(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioSessionHolder.sessionId = audioSessionId
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                LyricsStateHolder.isPlaying = isPlaying
                LyricsWidgetProvider.updateAllWidgets(this@PlaybackService)
            }
        })
    }

    private fun rebuildPlayerForAudioOutput() {
        val session = mediaSession ?: return
        val oldPlayer = activePlayer ?: return
        val oldSessionPlayer = sessionPlayer
        val mediaItems = List(oldPlayer.mediaItemCount) { oldPlayer.getMediaItemAt(it) }
        val currentIndex = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val currentPosition = oldPlayer.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldPlayer.playWhenReady
        val repeatMode = oldPlayer.repeatMode
        val shuffleModeEnabled = oldPlayer.shuffleModeEnabled
        val playbackParameters = oldPlayer.playbackParameters
        val volume = oldSessionPlayer?.volume ?: oldPlayer.volume

        val newPlayer = runCatching { createPlayer() }.getOrElse { error ->
            Log.e(TAG, "Create configured audio output failed; keeping current player", error)
            return
        }.apply {
            this.repeatMode = repeatMode
            this.shuffleModeEnabled = shuffleModeEnabled
            this.playbackParameters = playbackParameters
            this.volume = volume
            if (mediaItems.isNotEmpty()) {
                setMediaItems(
                    mediaItems,
                    currentIndex.coerceAtMost(mediaItems.lastIndex),
                    currentPosition
                )
                prepare()
            }
        }
        val newSessionPlayer = createSessionPlayer(newPlayer)
        runCatching {
            session.setPlayer(newSessionPlayer)
        }.onSuccess {
            lyricsBridgeJob?.cancel()
            oldSessionPlayer?.cancelPendingFade()
            oldPlayer.pause()
            oldPlayer.release()
            activePlayer = newPlayer
            sessionPlayer = newSessionPlayer
            newSessionPlayer.playWhenReady = playWhenReady
            AudioSessionHolder.sessionId = newPlayer.audioSessionId
            startLyricsBridge(newPlayer)
        }.onFailure { error ->
            Log.e(TAG, "Switch audio output failed; keeping current player", error)
            newSessionPlayer.release()
        }
    }

    private fun scheduleAudioOutputRebuild() {
        audioOutputRebuildJob?.cancel()
        audioOutputRebuildJob = serviceScope.launch {
            delay(150L)
            rebuildPlayerForAudioOutput()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player

        when (intent?.action) {
            ACTION_APPLY_AUDIO_OUTPUT -> scheduleAudioOutputRebuild()
            ACTION_SET_AUDIO_GAIN -> AudioOutputRuntime.setGainDb(
                intent.getFloatExtra(EXTRA_AUDIO_GAIN_DB, 0f)
            )
            ACTION_SET_PLAYER_VOLUME -> {
                val volume = intent.getFloatExtra(EXTRA_PLAYER_VOLUME, MusicVolumeSettings.volume)
                    .coerceIn(0f, 1f)
                val muted = intent.getBooleanExtra(EXTRA_PLAYER_MUTED, MusicVolumeSettings.muted)
                MusicVolumeSettings.setVolume(this, volume)
                MusicVolumeSettings.setMuted(this, muted)
                sessionPlayer?.setVolume(MusicVolumeSettings.effectiveVolume())
            }
            LyricsWidgetProvider.ACTION_TOGGLE_PLAY -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
            }
            LyricsWidgetProvider.ACTION_NEXT -> player?.seekToNextMediaItem()
            LyricsWidgetProvider.ACTION_PREV -> player?.seekToPreviousMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 将当前歌词行写入 MediaSession 元数据。Android Auto/AAOS/蓝牙车机可在媒体卡片上显示。
     * 服务后台播放时继续刷新，不依赖播放页保持前台。
     */
    private fun startLyricsBridge(player: ExoPlayer) {
        lyricsBridgeJob?.cancel()
        lyricsBridgeJob = serviceScope.launch {
            while (isActive) {
                LyricsStateHolder.updatePlaybackClock(
                    position = player.currentPosition.coerceAtLeast(0L),
                    playing = player.isPlaying,
                    duration = player.duration.takeIf { it > 0L }
                )
                LyricsProviderNotifier.notifyChanged(this@PlaybackService)
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
        sessionPlayer?.release()
        sessionPlayer = null
        activePlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
