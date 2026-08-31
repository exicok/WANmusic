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

object AudioFileSpectrum {
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
