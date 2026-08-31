package com.example.music

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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 频谱条数据 0f..1f。
 * 仅使用共享 Visualizer FFT；没有真实音频数据时保持静止。
 */
@Composable
fun rememberSpectrumBars(
    isPlaying: Boolean,
    enabled: Boolean,
    barCount: Int = 48,
    audioSessionIdProvider: () -> Int = { AudioSessionHolder.sessionId }
): FloatArray {
    val effectiveEnabled = enabled && AppAnimationSettings.enabled
    var bars by remember(barCount) { mutableStateOf(FloatArray(barCount)) }
    var sessionId by remember { mutableIntStateOf(audioSessionIdProvider()) }
    val smoothed = remember(barCount) { FloatArray(barCount) }

    LaunchedEffect(isPlaying, effectiveEnabled) {
        while (effectiveEnabled) {
            val id = audioSessionIdProvider()
            if (id != sessionId) {
                sessionId = id
                SharedAudioVisualizer.onSessionChanged(id)
            }
            if (!isPlaying || sessionId <= 0) {
                bars = FloatArray(barCount)
                smoothed.fill(0f)
            }
            delay(250L)
        }
    }

    DisposableEffect(effectiveEnabled, barCount) {
        if (!effectiveEnabled) {
            bars = FloatArray(barCount)
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
            }
            SharedAudioVisualizer.addListener(listener)
            SharedAudioVisualizer.onSessionChanged(audioSessionIdProvider())
            onDispose {
                SharedAudioVisualizer.removeListener(listener)
                bars = FloatArray(barCount)
                smoothed.fill(0f)
            }
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
    spectrumBars: FloatArray? = null,
    audioSessionIdProvider: () -> Int = { AudioSessionHolder.sessionId },
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors()
) {
    val liveBars = rememberSpectrumBars(
        isPlaying = isPlaying,
        enabled = spectrumEnabled && spectrumBars == null,
        barCount = 52,
        audioSessionIdProvider = audioSessionIdProvider
    )
    val bars = spectrumBars ?: liveBars
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
