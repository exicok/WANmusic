package com.example.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 播放服务写入的当前音频会话 ID，供 Visualizer 绑定。 */
object AudioSessionHolder {
    @Volatile
    var sessionId: Int = 0
}

/**
 * 专辑封面节奏能量 0f..1f。
 * 仅使用共享 Visualizer 取低频能量；没有真实音频数据时保持静止。
 */
@Composable
fun rememberBeatEnergy(
    isPlaying: Boolean,
    enabled: Boolean = true,
    audioSessionIdProvider: () -> Int = { AudioSessionHolder.sessionId }
): Float {
    val effectiveEnabled = enabled && AppAnimationSettings.enabled
    var energy by remember { mutableFloatStateOf(0f) }
    var sessionId by remember { mutableIntStateOf(audioSessionIdProvider()) }
    var smoothed by remember { mutableFloatStateOf(0f) }

    // 轮询会话 ID（播放服务异步挂载）
    LaunchedEffect(isPlaying, effectiveEnabled) {
        while (effectiveEnabled) {
            val id = audioSessionIdProvider()
            if (id != sessionId) {
                sessionId = id
                SharedAudioVisualizer.onSessionChanged(id)
            }
            if (!isPlaying || id <= 0) {
                energy = 0f
                smoothed = 0f
            }
            delay(250)
        }
    }

    DisposableEffect(effectiveEnabled) {
        if (!effectiveEnabled) {
            energy = 0f
            onDispose { }
        } else {
            val listener: (ByteArray) -> Unit = { fft ->
                val raw = SharedAudioVisualizer.bassEnergy(fft)
                smoothed = if (raw > smoothed) {
                    smoothed * 0.25f + raw * 0.75f
                } else {
                    smoothed * 0.82f + raw * 0.18f
                }
                energy = smoothed.coerceIn(0f, 1f)
            }
            SharedAudioVisualizer.addListener(listener)
            SharedAudioVisualizer.onSessionChanged(audioSessionIdProvider())
            onDispose {
                SharedAudioVisualizer.removeListener(listener)
                energy = 0f
                smoothed = 0f
            }
        }
    }

    return if (effectiveEnabled) energy else 0f
}

/** 根据能量计算封面缩放倍数（1f 为静止）。 */
fun beatScaleFromEnergy(energy: Float, maxExtra: Float = 0.08f): Float {
    return 1f + energy.coerceIn(0f, 1f) * maxExtra
}
