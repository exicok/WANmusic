package com.example.music

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class AudioOutputRenderersFactory(
    context: Context,
    private val outputConfig: AudioOutputConfig
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = buildAudioTrackSink(context)

    private fun buildAudioTrackSink(context: Context): AudioSink {
        val audioProcessors = buildList<AudioProcessor> {
            add(GainAudioProcessor(outputConfig.gainDb))
            if (outputConfig.resamplingEnabled) {
                add(
                    SonicAudioProcessor().apply {
                        setOutputSampleRateHz(outputConfig.resamplingSampleRateHz)
                    }
                )
            }
        }.toTypedArray()
        val defaultBufferProvider = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        val scaledBufferProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
                minBufferSizeInBytes,
                encoding,
                outputMode,
                pcmFrameSize,
                sampleRate,
                bitrate,
                maxAudioTrackPlaybackSpeed ->
            val defaultSize = defaultBufferProvider.getBufferSizeInBytes(
                minBufferSizeInBytes,
                encoding,
                outputMode,
                pcmFrameSize,
                sampleRate,
                bitrate,
                maxAudioTrackPlaybackSpeed
            )
            (defaultSize * outputConfig.audioTrackBufferMultiplier)
                .roundToInt()
                .coerceAtLeast(minBufferSizeInBytes)
        }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(
                outputConfig.audioTrackFloatOutput &&
                    outputConfig.gainDb == 0f &&
                    !outputConfig.resamplingEnabled
            )
            .setEnableAudioTrackPlaybackParams(outputConfig.audioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(scaledBufferProvider)
            .setAudioProcessors(audioProcessors)
            .build()
    }
}
