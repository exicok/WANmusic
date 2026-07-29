package com.example.music

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频谱条数据 0f..1f。
 * 优先共享 Visualizer FFT；失败时在播放中合成动态频谱。
 */
@Composable
fun rememberSpectrumBars(
    isPlaying: Boolean,
    enabled: Boolean,
    barCount: Int = 48
): FloatArray {
    val effectiveEnabled = enabled && AppAnimationSettings.enabled
    var bars by remember(barCount) { mutableStateOf(FloatArray(barCount)) }
    var sessionId by remember { mutableIntStateOf(AudioSessionHolder.sessionId) }
    var useVisualizer by remember { mutableStateOf(false) }
    val smoothed = remember(barCount) { FloatArray(barCount) }

    LaunchedEffect(isPlaying, effectiveEnabled) {
        while (effectiveEnabled) {
            val id = AudioSessionHolder.sessionId
            if (id != sessionId) {
                sessionId = id
                SharedAudioVisualizer.onSessionChanged(id)
            }
            delay(400)
        }
    }

    DisposableEffect(effectiveEnabled, barCount) {
        if (!effectiveEnabled) {
            bars = FloatArray(barCount)
            useVisualizer = false
            onDispose { }
        } else {
            val listener: (ByteArray) -> Unit = listener@{ fft ->
                if (fft.size < 4) return@listener
                fillBarsFromFft(fft, smoothed)
                val out = FloatArray(smoothed.size)
                for (i in smoothed.indices) {
                    val prev = bars.getOrElse(i) { 0f }
                    val raw = smoothed[i]
                    out[i] = if (raw > prev) {
                        prev * 0.25f + raw * 0.75f
                    } else {
                        prev * 0.78f + raw * 0.22f
                    }
                }
                bars = out
                useVisualizer = true
            }
            SharedAudioVisualizer.addListener(listener)
            SharedAudioVisualizer.onSessionChanged(AudioSessionHolder.sessionId)
            useVisualizer = SharedAudioVisualizer.isAttached
            onDispose {
                SharedAudioVisualizer.removeListener(listener)
                if (!effectiveEnabled) bars = FloatArray(barCount)
            }
        }
    }

    // 合成频谱兜底
    LaunchedEffect(isPlaying, effectiveEnabled, useVisualizer, sessionId, barCount) {
        if (!effectiveEnabled) {
            bars = FloatArray(barCount)
            return@LaunchedEffect
        }
        while (true) {
            if (!isPlaying) {
                val decayed = FloatArray(barCount) { i ->
                    (bars.getOrElse(i) { 0f } * 0.88f).let { if (it < 0.02f) 0f else it }
                }
                bars = decayed
                delay(32)
                continue
            }
            // 已有真实频谱时只做轻量保活，避免双轨抢写
            if (useVisualizer && SharedAudioVisualizer.isAttached && sessionId > 0) {
                delay(80)
                // 若长时间无回调，回退合成
                continue
            }
            val now = SystemClock.uptimeMillis()
            val next = FloatArray(barCount) { i ->
                val t = now / 1000f
                val phase = i * 0.35f
                val wave = (
                    0.45f + 0.35f * sin(t * 3.1f + phase) +
                        0.20f * cos(t * 5.4f + phase * 1.7f) +
                        0.15f * sin(t * 1.2f + i * 0.08f)
                    ).coerceIn(0f, 1f)
                val bassBoost = 1f - (i.toFloat() / barCount) * 0.45f
                (wave * bassBoost).coerceIn(0f, 1f)
            }
            val blended = FloatArray(barCount) { i ->
                val prev = bars.getOrElse(i) { 0f }
                val raw = next[i]
                if (raw > prev) prev * 0.3f + raw * 0.7f else prev * 0.75f + raw * 0.25f
            }
            bars = blended
            delay(16L)
        }
    }

    return if (effectiveEnabled) bars else FloatArray(barCount)
}

/** 将 FFT 映射为对数频段频谱条 */
private fun fillBarsFromFft(fft: ByteArray, out: FloatArray) {
    val n = out.size
    val pairs = fft.size / 2
    if (pairs <= 2 || n == 0) {
        out.fill(0f)
        return
    }
    val usable = (pairs - 1).coerceAtLeast(1)
    for (i in 0 until n) {
        val t0 = i.toFloat() / n
        val t1 = (i + 1).toFloat() / n
        val start = 1 + (usable * (t0 * t0)).toInt().coerceIn(0, usable - 1)
        val end = 1 + (usable * (t1 * t1)).toInt().coerceIn(start + 1, usable)

        var sum = 0.0
        var count = 0
        for (bin in start until end) {
            val re = fft[bin * 2].toInt()
            val im = fft[bin * 2 + 1].toInt()
            sum += sqrt((re * re + im * im).toDouble())
            count++
        }
        val avg = if (count > 0) (sum / count).toFloat() else 0f
        val norm = (avg / 36f).coerceIn(0f, 2.5f)
        out[i] = (1f - 1f / (1f + norm * 1.8f)).coerceIn(0f, 1f)
    }
}

/**
 * 频谱叠加风格进度条：底层频谱柱 + 上层可拖动进度。
 * spectrumEnabled = false 时退回普通进度条。
 */
@Composable
fun SpectrumProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    isPlaying: Boolean,
    spectrumEnabled: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors()
) {
    val bars = rememberSpectrumBars(
        isPlaying = isPlaying,
        enabled = spectrumEnabled,
        barCount = 52
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (spectrumEnabled) 52.dp else 40.dp),
        contentAlignment = Alignment.Center
    ) {
        if (spectrumEnabled) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.Center)
            ) {
                val count = bars.size.coerceAtLeast(1)
                val gap = 2.dp.toPx()
                val totalGap = gap * (count - 1)
                val barWidth = ((size.width - totalGap) / count).coerceAtLeast(1f)
                val progressX = size.width * value.coerceIn(0f, 1f)
                val maxH = size.height * 0.92f
                val minH = 3.dp.toPx()
                val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

                for (i in 0 until count) {
                    val level = bars[i].coerceIn(0f, 1f)
                    val h = max(minH, maxH * (0.12f + level * 0.88f))
                    val left = i * (barWidth + gap)
                    val top = (size.height - h) / 2f
                    val centerX = left + barWidth / 2f
                    val played = centerX <= progressX
                    val base = if (played) {
                        lerp(primary, secondary, level * 0.55f)
                    } else {
                        onSurface.copy(alpha = 0.18f + level * 0.28f)
                    }
                    drawRoundRect(
                        color = base,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, h),
                        cornerRadius = radius
                    )
                }

                // 进度位置细线
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(
                            primary.copy(alpha = 0.15f),
                            primary,
                            primary.copy(alpha = 0.15f)
                        )
                    ),
                    start = Offset(progressX, 0f),
                    end = Offset(progressX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = if (spectrumEnabled) {
                SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            } else {
                colors
            }
        )
    }
}
