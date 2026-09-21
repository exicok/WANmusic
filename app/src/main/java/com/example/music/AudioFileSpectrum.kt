package com.example.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

@Composable
fun rememberAudioFileSpectrum(
    context: Context,
    uri: Uri,
    positionMs: Long,
    enabled: Boolean,
    bandCount: Int = 80
): FloatArray {
    val count = bandCount.coerceIn(1, 80)
    var bands by remember(uri, count) { mutableStateOf(FloatArray(count)) }
    val analysisPosition = (positionMs.coerceAtLeast(0L) / 80L) * 80L
    LaunchedEffect(uri, analysisPosition, enabled, count) {
        bands = if (enabled) {
            withContext(Dispatchers.IO) {
                AudioFileSpectrum.read(context, uri, analysisPosition, count)
            }
        } else {
            FloatArray(count)
        }
    }
    return bands
}

/** 整首曲目按时间轴聚合的频谱（每个柱=一个时间片），每曲目只算一次。 */
@Composable
fun rememberAudioTrackSpectrum(
    context: Context,
    uri: Uri,
    enabled: Boolean,
    bandCount: Int = 80
): FloatArray {
    val count = bandCount.coerceIn(1, 120)
    var bands by remember(uri, count) {
        mutableStateOf(AudioFileSpectrum.cachedTrackSpectrum(uri, count) ?: FloatArray(count))
    }
    LaunchedEffect(uri, enabled, count) {
        if (!enabled) return@LaunchedEffect
        val cached = AudioFileSpectrum.cachedTrackSpectrum(uri, count)
        if (cached != null) {
            bands = cached
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { AudioFileSpectrum.trackSpectrum(context, uri, count) }
                .getOrElse { FloatArray(count) }
        }
        AudioFileSpectrum.cacheTrackSpectrum(uri, count, result)
        bands = result
    }
    return bands
}

object AudioFileSpectrum {
    private val trackCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    fun cachedTrackSpectrum(uri: Uri, bandCount: Int): FloatArray? =
        trackCache["${uri}|$bandCount"]

    fun cacheTrackSpectrum(uri: Uri, bandCount: Int, value: FloatArray) {
        if (trackCache.size > 24) trackCache.clear()
        trackCache["${uri}|$bandCount"] = value
    }

    /** 整曲解码为时间轴能量曲线：每柱代表一个时间片的平均频谱能量。 */
    fun trackSpectrum(
        context: Context,
        uri: Uri,
        bandCount: Int
    ): FloatArray {
        val energies = ArrayList<Float>()
        val window = ShortArray(2048)
        var filled = 0
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return FloatArray(bandCount)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(bandCount)
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var guard = 0
            while (!outputEnded && guard++ < 200_000) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        if (input != null) {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex >= 0) {
                    if (info.size >= 2) {
                        codec.getOutputBuffer(outputIndex)?.let { output ->
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            val shorts = output.asShortBuffer()
                            while (shorts.hasRemaining()) {
                                window[filled++] = shorts.get()
                                if (filled == window.size) {
                                    energies += AudioSpectrumAnalyzer
                                        .fftBands(window, 24)
                                        .average()
                                        .toFloat()
                                    filled = 0
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        if (energies.isEmpty()) return FloatArray(bandCount)
        val peak = energies.max().coerceAtLeast(0.02f)
        return FloatArray(bandCount) { index ->
            val from = (index.toLong() * energies.size / bandCount).toInt()
                .coerceIn(0, energies.lastIndex)
            val to = ((index + 1).toLong() * energies.size / bandCount).toInt()
                .coerceIn(from + 1, energies.size)
            (energies.subList(from, to).average().toFloat() / peak).coerceIn(0f, 1f)
        }
    }

    fun read(
        context: Context,
        uri: Uri,
        positionMs: Long,
        bandCount: Int
    ): FloatArray = runCatching {
        val pcm = decodePcmWindow(context, uri, positionMs * 1_000L, 4096)
        AudioSpectrumAnalyzer.fftBands(pcm, bandCount)
    }.getOrElse { FloatArray(bandCount) }

    private fun decodePcmWindow(
        context: Context,
        uri: Uri,
        targetUs: Long,
        wantedSamples: Int
    ): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return ShortArray(0)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
            extractor.selectTrack(track)
            extractor.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val result = ShortArray(wantedSamples)
            val info = MediaCodec.BufferInfo()
            var written = 0
            var inputEnded = false
            var outputEnded = false
            var attempts = 0
            while (!outputEnded && written < wantedSamples && attempts < 200) {
                attempts++
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(5_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 5_000L)
                if (outputIndex >= 0) {
                    if (info.presentationTimeUs >= targetUs && info.size >= 2) {
                        codec.getOutputBuffer(outputIndex)?.let { output ->
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            val shorts = output.asShortBuffer()
                            val copyCount = minOf(shorts.remaining(), wantedSamples - written)
                            shorts.get(result, written, copyCount)
                            written += copyCount
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
            return result.copyOf(written)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
