package com.example.music

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dockContentPadding = LocalDockContentPadding.current
    val preferences = remember {
        context.getSharedPreferences(AudioOutputConfig.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var config by remember { mutableStateOf(AudioOutputConfig.load(preferences)) }
    var applyStatus by remember { mutableStateOf("") }
    val audioOutput = rememberAudioOutputSnapshot()
    val configuredLatencyMs = configuredOutputLatencyMs(config, audioOutput.sampleRateHz)
    val chinese = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"
    fun text(zh: String, en: String): String = if (chinese) zh else en

    fun canApplyGainWithoutRebuild(): Boolean =
        !config.audioTrackFloatOutput || config.resamplingEnabled

    fun updateOutputConfig(
        value: AudioOutputConfig,
        gainOnly: Boolean = false,
        playbackOnly: Boolean = false
    ) {
        val normalized = value.normalized()
        if (normalized == config) return
        config = normalized
        config.save(preferences)
        if (playbackOnly) {
            applyStatus = text("淡入淡出设置已实时更新", "Fade settings updated")
            return
        }
        val intent = Intent(context, PlaybackService::class.java).apply {
            if (gainOnly) {
                action = PlaybackService.ACTION_SET_AUDIO_GAIN
                putExtra(PlaybackService.EXTRA_AUDIO_GAIN_DB, config.gainDb)
            } else {
                action = PlaybackService.ACTION_APPLY_AUDIO_OUTPUT
            }
        }
        val applied = runCatching { context.startService(intent) }.isSuccess
        applyStatus = when {
            !applied -> text("配置已保存，将在下次播放时应用", "Saved; applies on next playback")
            gainOnly -> text("主增益已实时更新", "Gain updated")
            else -> text("AudioTrack 配置已实时更新", "AudioTrack configuration updated")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appText("音频输出与增益", "Audio output and gain")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, appText("返回", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = dockContentPadding)
        ) {
            SettingsSectionHeader(appText("当前输出", "Current output"))
            ListItem(
                headlineContent = { Text(audioOutput.summary) },
                supportingContent = {
                    Text(
                        buildString {
                            append(appText("AudioTrack · 配置缓冲约 ${configuredLatencyMs}ms", "AudioTrack · configured buffer about ${configuredLatencyMs}ms"))
                            append("\n")
                            append(audioOutput.latencyLabel)
                            val details = buildList {
                                audioOutput.systemLatencyMs?.let { add(appText("系统 ${it}ms", "System ${it}ms")) }
                                audioOutput.bufferLatencyMs?.let { add(appText("缓冲 ${it}ms", "Buffer ${it}ms")) }
                                audioOutput.sampleRateHz?.let { add("${it}Hz") }
                            }
                            if (details.isNotEmpty()) append(" · ${details.joinToString(" · ")}")
                            if (audioOutput.allOutputs.size > 1) {
                                append(appText("\n可用：", "\nAvailable: "))
                                append(audioOutput.allOutputs.joinToString("、"))
                            }
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Headset, null) }
            )

            SettingsSectionHeader(appText("主增益", "Master gain"))
            ListItem(
                headlineContent = { Text("${formatGain(config.gainDb)} dB") },
                supportingContent = {
                    Text(if (config.gainDb > 0f) appText("正增益可能造成削波失真", "Positive gain may cause clipping") else appText("软件 PCM 增益", "Software PCM gain"))
                },
                leadingContent = { Icon(Icons.Default.Speaker, null) },
                trailingContent = {
                    IconButton(
                        onClick = {
                            updateOutputConfig(
                                config.copy(gainDb = 0f),
                                gainOnly = canApplyGainWithoutRebuild()
                            )
                        }
                    ) {
                        Icon(Icons.Default.Refresh, appText("重置增益", "Reset gain"))
                    }
                }
            )
            Slider(
                value = config.gainDb,
                onValueChange = {
                    updateOutputConfig(
                        config.copy(gainDb = (it * 2f).roundToInt() / 2f),
                        gainOnly = canApplyGainWithoutRebuild()
                    )
                },
                valueRange = AudioOutputConfig.MIN_GAIN_DB..AudioOutputConfig.MAX_GAIN_DB,
                steps = 71,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            AudioTrackOutputSettings(
                config = config,
                onChange = { updateOutputConfig(it) },
                onPlaybackChange = { updateOutputConfig(it, playbackOnly = true) }
            )

            if (applyStatus.isNotBlank()) {
                Text(
                    text = applyStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioTrackOutputSettings(
    config: AudioOutputConfig,
    onChange: (AudioOutputConfig) -> Unit,
    onPlaybackChange: (AudioOutputConfig) -> Unit
) {
    SettingsSectionHeader(appText("播放过渡", "Playback transitions"))
    OutputSwitchItem(
        title = appText("淡入淡出", "Fade in/out"),
        description = appText("播放和暂停时平滑调整音量", "Smooth volume when playing and pausing"),
        checked = config.fadeEnabled,
        onCheckedChange = { onPlaybackChange(config.copy(fadeEnabled = it)) }
    )
    if (config.fadeEnabled) {
        ListItem(
            headlineContent = { Text(appText("过渡时长 ${config.fadeDurationMs}ms", "Duration ${config.fadeDurationMs}ms")) },
            supportingContent = { Text(appText("设置会在下一次播放或暂停时生效", "Applies on the next play or pause")) }
        )
        Slider(
            value = config.fadeDurationMs.toFloat(),
            onValueChange = {
                val duration = (it / 100f).roundToInt() * 100
                onPlaybackChange(config.copy(fadeDurationMs = duration))
            },
            valueRange = AudioOutputConfig.MIN_FADE_DURATION_MS.toFloat()..
                AudioOutputConfig.MAX_FADE_DURATION_MS.toFloat(),
            steps = 18,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    SettingsSectionHeader(appText("重采样", "Resampling"))
    OutputSwitchItem(
        title = appText("启用重采样", "Enable resampling"),
        description = if (config.resamplingEnabled) {
            appText("统一输出为 ${formatSampleRate(config.resamplingSampleRateHz)}", "Output at ${formatSampleRate(config.resamplingSampleRateHz)}")
        } else {
            appText("保持音源原始采样率", "Keep the source sample rate")
        },
        checked = config.resamplingEnabled,
        onCheckedChange = { onChange(config.copy(resamplingEnabled = it)) }
    )
    if (config.resamplingEnabled) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            AudioOutputConfig.SUPPORTED_SAMPLE_RATES.forEachIndexed { index, sampleRate ->
                SegmentedButton(
                    selected = config.resamplingSampleRateHz == sampleRate,
                    onClick = { onChange(config.copy(resamplingSampleRateHz = sampleRate)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index,
                        AudioOutputConfig.SUPPORTED_SAMPLE_RATES.size
                    )
                ) {
                    Text(formatSampleRate(sampleRate))
                }
            }
        }
    }

    SettingsSectionHeader(appText("AudioTrack 详细设置", "AudioTrack details"))
    ListItem(
        headlineContent = { Text(appText("缓冲倍率 ${formatDecimal(config.audioTrackBufferMultiplier)}x", "Buffer multiplier ${formatDecimal(config.audioTrackBufferMultiplier)}x")) },
        supportingContent = { Text(appText("更小延迟与更稳定播放之间的平衡", "Balance lower latency and stable playback")) }
    )
    Slider(
        value = config.audioTrackBufferMultiplier,
        onValueChange = {
            onChange(config.copy(audioTrackBufferMultiplier = (it * 2f).roundToInt() / 2f))
        },
        valueRange = 1f..4f,
        steps = 5,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    OutputSwitchItem(
        title = appText("32 位浮点输出", "32-bit float output"),
        description = if (config.gainDb == 0f && !config.resamplingEnabled) {
            appText("设备支持时保留浮点 PCM", "Preserve float PCM when supported")
        } else {
            appText("启用主增益或重采样时自动使用可处理的 PCM 路径", "Uses a processable PCM path with gain or resampling")
        },
        checked = config.audioTrackFloatOutput,
        onCheckedChange = { onChange(config.copy(audioTrackFloatOutput = it)) }
    )
    OutputSwitchItem(
        title = appText("系统变速参数", "System speed parameters"),
        description = appText("由 AudioTrack 处理播放速度；关闭时由 Media3 软件处理", "Let AudioTrack handle speed; otherwise Media3 handles it"),
        checked = config.audioTrackPlaybackParams,
        onCheckedChange = { onChange(config.copy(audioTrackPlaybackParams = it)) }
    )
}

@Composable
private fun OutputSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

private fun formatGain(value: Float): String = if (value > 0f) {
    "+${formatDecimal(value)}"
} else {
    formatDecimal(value)
}

private fun formatDecimal(value: Float): String = if (value % 1f == 0f) {
    value.toInt().toString()
} else {
    String.format(java.util.Locale.US, "%.1f", value)
}

private fun formatSampleRate(sampleRateHz: Int): String = when (sampleRateHz) {
    44_100 -> "44.1 kHz"
    else -> "${sampleRateHz / 1_000} kHz"
}

private fun configuredOutputLatencyMs(config: AudioOutputConfig, sampleRateHz: Int?): Int {
    val sampleRate = sampleRateHz?.takeIf { it > 0 } ?: 48_000
    val baseFrames = sampleRate / 50
    val frames = (baseFrames * config.audioTrackBufferMultiplier).roundToInt()
    return (frames * 1_000f / sampleRate).roundToInt().coerceAtLeast(1)
}
