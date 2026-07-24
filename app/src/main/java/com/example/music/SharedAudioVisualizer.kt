package com.example.music

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * 全局共享 Visualizer：同一音频会话只挂一个，避免封面跳动与频谱进度条互相抢占失败。
 * 回调已切换到主线程，可安全写入 Compose 状态。
 */
object SharedAudioVisualizer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private var visualizer: Visualizer? = null
    private var boundSessionId: Int = -1
    private var captureSize: Int = 0

    @Synchronized
    fun addListener(listener: (ByteArray) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        ensureAttached(AudioSessionHolder.sessionId)
    }

    @Synchronized
    fun removeListener(listener: (ByteArray) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            releaseInternal()
        }
    }

    @Synchronized
    fun onSessionChanged(sessionId: Int) {
        if (listeners.isEmpty()) return
        if (sessionId != boundSessionId) {
            ensureAttached(sessionId)
        }
    }

    @Synchronized
    private fun ensureAttached(sessionId: Int) {
        if (sessionId <= 0) {
            releaseInternal()
            return
        }
        if (visualizer != null && boundSessionId == sessionId) return
        releaseInternal()
        runCatching {
            val v = Visualizer(sessionId)
            val range = Visualizer.getCaptureSizeRange()
            val size = range[1].coerceAtMost(512).coerceAtLeast(range[0])
            v.captureSize = size
            captureSize = size
            val rate = (Visualizer.getMaxCaptureRate() * 0.65f).toInt().coerceAtLeast(8_000)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 4) return
                        // 拷贝一份，避免底层复用缓冲导致并发读坏数据
                        val copy = fft.copyOf()
                        mainHandler.post {
                            for (l in listeners) {
                                runCatching { l(copy) }
                            }
                        }
                    }
                },
                rate,
                false,
                true
            )
            v.enabled = true
            visualizer = v
            boundSessionId = sessionId
        }.onFailure {
            android.util.Log.w("SharedAudioVisualizer", "attach failed: ${it.message}")
            releaseInternal()
        }
    }

    @Synchronized
    private fun releaseInternal() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        boundSessionId = -1
        captureSize = 0
    }

    val isAttached: Boolean
        @Synchronized get() = visualizer != null

    /** 低频能量 0f..1f */
    fun bassEnergy(fft: ByteArray): Float {
        if (fft.size < 8) return 0f
        val bins = (fft.size / 2).coerceAtMost(12)
        var sum = 0.0
        var count = 0
        for (i in 1 until bins) {
            val re = fft[i * 2].toInt()
            val im = fft[i * 2 + 1].toInt()
            sum += sqrt((re * re + im * im).toDouble())
            count++
        }
        if (count == 0) return 0f
        val avg = (sum / count).toFloat()
        val norm = (avg / 40f).coerceIn(0f, 2.5f)
        return (1f - 1f / (1f + norm * 2f)).coerceIn(0f, 1f)
    }
}
