package com.example.music

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.sin

/** 播放服务写入的当前音频会话 ID，供 Visualizer 绑定。 */
object AudioSessionHolder {
    @Volatile
    var sessionId: Int = 0
}

/**
 * 专辑封面节奏能量 0f..1f。
 * 优先用共享 Visualizer 取低频能量；失败时在播放中使用合成节拍。
 */
@Composable
fun rememberBeatEnergy(
    isPlaying: Boolean,
    enabled: Boolean = true
): Float {
    var energy by remember { mutableFloatStateOf(0f) }
    var sessionId by remember { mutableIntStateOf(AudioSessionHolder.sessionId) }
    var useVisualizer by remember { mutableStateOf(false) }
    var smoothed by remember { mutableFloatStateOf(0f) }

    // 轮询会话 ID（播放服务异步挂载）
    LaunchedEffect(isPlaying, enabled) {
        while (enabled) {
            val id = AudioSessionHolder.sessionId
            if (id != sessionId) {
                sessionId = id
                SharedAudioVisualizer.onSessionChanged(id)
            }
            delay(400)
        }
    }

    DisposableEffect(enabled) {
        if (!enabled) {
            energy = 0f
            useVisualizer = false
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
                useVisualizer = true
            }
            SharedAudioVisualizer.addListener(listener)
            SharedAudioVisualizer.onSessionChanged(AudioSessionHolder.sessionId)
            useVisualizer = SharedAudioVisualizer.isAttached
            onDispose {
                SharedAudioVisualizer.removeListener(listener)
                energy = 0f
            }
        }
    }

    // 合成节拍兜底 / 未播放时归零
    LaunchedEffect(isPlaying, enabled, useVisualizer, sessionId) {
        if (!enabled) {
            energy = 0f
            return@LaunchedEffect
        }
        while (true) {
            if (!isPlaying) {
                energy = (energy * 0.85f).coerceAtLeast(0f)
                if (energy < 0.01f) energy = 0f
                delay(32)
                continue
            }
            if (useVisualizer && SharedAudioVisualizer.isAttached && sessionId > 0) {
                delay(80)
                continue
            }
            // 合成节拍兜底
            val t = SystemClock.uptimeMillis() / 1000.0
            val pulse = (
                0.35 + 0.35 * sin(t * 2.2 * Math.PI) +
                    0.20 * sin(t * 4.4 * Math.PI + 0.7) +
                    0.15 * sin(t * 1.1 * Math.PI)
                ).toFloat().coerceIn(0f, 1f)
            energy = if (pulse > energy) {
                energy * 0.3f + pulse * 0.7f
            } else {
                energy * 0.8f + pulse * 0.2f
            }
            delay(SmoothAnimationFrameRate.frameDelayMillis)
        }
    }

    return if (enabled) energy else 0f
}

/** 根据能量计算封面缩放倍数（1f 为静止）。 */
fun beatScaleFromEnergy(energy: Float, maxExtra: Float = 0.08f): Float {
    return 1f + energy.coerceIn(0f, 1f) * maxExtra
}
