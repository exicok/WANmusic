package com.example.music

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@UnstableApi
class FadePlaybackPlayer(
    private val delegate: Player,
    private val scope: CoroutineScope,
    private val configProvider: () -> AudioOutputConfig
) : ForwardingPlayer(delegate) {
    private var fadeJob: Job? = null
    private var targetVolume = delegate.volume.coerceIn(0f, 1f)
    private var pausePending = false

    override fun play() {
        if (delegate.playWhenReady && !pausePending) return
        val config = configProvider()
        fadeJob?.cancel()
        pausePending = false
        if (!config.fadeEnabled) {
            delegate.volume = targetVolume
            if (!delegate.playWhenReady) delegate.play()
            return
        }
        if (!delegate.playWhenReady) {
            delegate.volume = 0f
            delegate.play()
        }
        fadeTo(targetVolume, config.fadeDurationMs)
    }

    override fun pause() {
        if (!delegate.playWhenReady) {
            delegate.pause()
            return
        }
        val config = configProvider()
        fadeJob?.cancel()
        if (!config.fadeEnabled) {
            pausePending = false
            delegate.pause()
            delegate.volume = targetVolume
            return
        }
        val startVolume = delegate.volume.coerceIn(0f, 1f)
        pausePending = true
        fadeJob = scope.launch {
            animateVolume(startVolume, 0f, config.fadeDurationMs)
            delegate.pause()
            delegate.volume = targetVolume
            pausePending = false
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun setVolume(volume: Float) {
        targetVolume = volume.coerceIn(0f, 1f)
        if (fadeJob?.isActive != true) delegate.volume = targetVolume
    }

    override fun getVolume(): Float = targetVolume

    override fun release() {
        fadeJob?.cancel()
        pausePending = false
        super.release()
    }

    fun cancelPendingFade() {
        fadeJob?.cancel()
        fadeJob = null
        pausePending = false
        delegate.volume = targetVolume
    }

    private fun fadeTo(volume: Float, durationMs: Int) {
        fadeJob = scope.launch {
            animateVolume(delegate.volume, volume, durationMs)
        }
    }

    private suspend fun animateVolume(from: Float, to: Float, durationMs: Int) {
        val duration = durationMs.coerceAtLeast(1)
        val steps = (duration / FRAME_DELAY_MS.toFloat()).roundToInt().coerceAtLeast(1)
        repeat(steps) { index ->
            val progress = (index + 1f) / steps
            delegate.volume = from + (to - from) * progress
            delay(FRAME_DELAY_MS)
        }
        delegate.volume = to
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
    }
}
