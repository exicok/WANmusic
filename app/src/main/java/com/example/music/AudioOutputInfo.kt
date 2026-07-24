package com.example.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** 当前媒体音频输出设备快照（含延迟估算） */
data class AudioOutputSnapshot(
    /** 主输出类型，如「蓝牙」「内置扬声器」 */
    val typeLabel: String,
    /** 设备产品名（可能为空） */
    val productName: String,
    /** 一行摘要：类型 · 名称 */
    val summary: String,
    /** 全部输出设备摘要列表 */
    val allOutputs: List<String>,
    val deviceCount: Int,
    /** 预估输出延迟（毫秒），无法取得时为 null */
    val latencyMs: Int?,
    /** 延迟展示文案，如「约 180 ms」 */
    val latencyLabel: String,
    /** 系统报告的输出延迟（若可读） */
    val systemLatencyMs: Int?,
    /** 输出缓冲估算延迟 */
    val bufferLatencyMs: Int?,
    val sampleRateHz: Int?,
    val bufferFrames: Int?,
    /** 设备类型附加延迟（如蓝牙编解码） */
    val deviceExtraLatencyMs: Int
)

object AudioOutputProbe {

    fun snapshot(context: Context): AudioOutputSnapshot {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val active = resolveActiveOutputs(am)
        val all = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .sortedWith(
                compareByDescending<AudioDeviceInfo> { priority(it.type) }
                    .thenBy { it.productName?.toString().orEmpty() }
            )

        val primary = active.firstOrNull() ?: all.firstOrNull()
        val typeLabel = primary?.let { typeLabel(it.type) } ?: "未知"
        val productName = primary?.productName?.toString()?.trim().orEmpty()
        val summary = when {
            productName.isNotEmpty() && productName != typeLabel -> "$typeLabel · $productName"
            else -> typeLabel
        }
        val allSummaries = all.map { deviceSummary(it) }.distinct()
        val latency = estimateLatency(am, primary)

        return AudioOutputSnapshot(
            typeLabel = typeLabel,
            productName = productName,
            summary = summary,
            allOutputs = allSummaries,
            deviceCount = all.size,
            latencyMs = latency.totalMs,
            latencyLabel = latency.label,
            systemLatencyMs = latency.systemMs,
            bufferLatencyMs = latency.bufferMs,
            sampleRateHz = latency.sampleRateHz,
            bufferFrames = latency.bufferFrames,
            deviceExtraLatencyMs = latency.deviceExtraMs
        )
    }

    private data class LatencyEstimate(
        val totalMs: Int?,
        val label: String,
        val systemMs: Int?,
        val bufferMs: Int?,
        val sampleRateHz: Int?,
        val bufferFrames: Int?,
        val deviceExtraMs: Int
    )

    private fun estimateLatency(am: AudioManager, device: AudioDeviceInfo?): LatencyEstimate {
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        val bufferMs = if (sampleRate != null && frames != null) {
            ((frames * 1000.0) / sampleRate).roundToInt().coerceAtLeast(1)
        } else {
            null
        }

        val systemMs = readSystemOutputLatencyMs(am)
        val deviceExtra = deviceTypeExtraLatencyMs(device?.type)

        // 组合策略：
        // - 有系统延迟时以其为主，蓝牙等再叠加编解码附加（系统值偏小时）
        // - 否则用缓冲延迟 + 设备附加
        val total = when {
            systemMs != null && systemMs > 0 -> {
                val merged = if (deviceExtra > 0 && systemMs < deviceExtra) {
                    systemMs + deviceExtra
                } else if (deviceExtra > 0 && systemMs < deviceExtra + 40) {
                    // 系统值可能未含完整蓝牙链路
                    maxOf(systemMs, deviceExtra + (bufferMs ?: 20))
                } else {
                    systemMs
                }
                merged.coerceIn(1, 2_000)
            }
            bufferMs != null -> (bufferMs + deviceExtra).coerceIn(1, 2_000)
            deviceExtra > 0 -> deviceExtra.coerceIn(1, 2_000)
            else -> null
        }

        val label = when {
            total == null -> "延迟未知"
            deviceExtra >= 100 -> "约 ${total} ms（含无线）"
            else -> "约 ${total} ms"
        }

        return LatencyEstimate(
            totalMs = total,
            label = label,
            systemMs = systemMs,
            bufferMs = bufferMs,
            sampleRateHz = sampleRate,
            bufferFrames = frames,
            deviceExtraMs = deviceExtra
        )
    }

    /** 隐藏 API：AudioManager.getOutputLatency(STREAM_MUSIC)，失败返回 null */
    private fun readSystemOutputLatencyMs(am: AudioManager): Int? {
        return runCatching {
            val method = AudioManager::class.java.getMethod(
                "getOutputLatency",
                Int::class.javaPrimitiveType
            )
            val value = method.invoke(am, AudioManager.STREAM_MUSIC) as? Int
            value?.takeIf { it in 1..5_000 }
        }.getOrNull()
    }

    private fun deviceTypeExtraLatencyMs(type: Int?): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 160
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 200
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 120
        AudioDeviceInfo.TYPE_HEARING_AID -> 80
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 8
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 5
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC -> 40
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 10
        else -> {
            if (type != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_BROADCAST
            ) {
                140
            } else {
                0
            }
        }
    }

    private fun resolveActiveOutputs(am: AudioManager): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val routed = runCatching { am.getAudioDevicesForAttributes(attrs) }.getOrNull().orEmpty()
            if (routed.isNotEmpty()) return routed.filter { it.isSink }
        }

        val sinks = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        if (sinks.isEmpty()) return emptyList()

        val external = sinks
            .filter { !isBuiltin(it.type) }
            .sortedByDescending { priority(it.type) }
        if (external.isNotEmpty()) return listOf(external.first())

        return sinks.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .ifEmpty { listOf(sinks.first()) }
    }

    private fun isBuiltin(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "内置扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机（带麦）"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "线路输出"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字输出"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音"
        AudioDeviceInfo.TYPE_IP -> "网络音频"
        AudioDeviceInfo.TYPE_BUS -> "总线音频"
        AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙 LE 音箱"
        AudioDeviceInfo.TYPE_DOCK -> "底座"
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_BROADCAST
            ) {
                "蓝牙广播"
            } else {
                "其它输出"
            }
        }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 100
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 90
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 80
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> 70
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 60
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 10
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 5
        else -> 20
    }

    private fun deviceSummary(device: AudioDeviceInfo): String {
        val type = typeLabel(device.type)
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name.isNotEmpty() && !name.equals(type, ignoreCase = true)) {
            "$type · $name"
        } else {
            type
        }
    }
}

/** 监听系统输出设备变化，返回当前媒体输出快照。 */
@Composable
fun rememberAudioOutputSnapshot(): AudioOutputSnapshot {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AudioOutputProbe.snapshot(context)) }

    DisposableEffect(context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable {
            snapshot = AudioOutputProbe.snapshot(context)
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                handler.post(refresh)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                handler.post(refresh)
            }
        }
        am.registerAudioDeviceCallback(callback, handler)
        handler.post(refresh)
        val periodic = object : Runnable {
            override fun run() {
                snapshot = AudioOutputProbe.snapshot(context)
                handler.postDelayed(this, 2_500L)
            }
        }
        handler.postDelayed(periodic, 2_500L)
        onDispose {
            handler.removeCallbacks(refresh)
            handler.removeCallbacks(periodic)
            runCatching { am.unregisterAudioDeviceCallback(callback) }
        }
    }
    return snapshot
}
