package com.example.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class GainAudioProcessor(gainDb: Float) : BaseAudioProcessor() {
    init {
        AudioOutputRuntime.setGainDb(gainDb)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, output)
            C.ENCODING_PCM_24BIT -> processInt24(inputBuffer, output)
            C.ENCODING_PCM_32BIT -> processInt32(inputBuffer, output)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
        }
        output.flip()
    }

    private fun processInt16(input: ByteBuffer, output: ByteBuffer) {
        val linearGain = AudioOutputRuntime.linearGain
        while (input.remaining() >= Short.SIZE_BYTES) {
            val sample = (input.short * linearGain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(sample.toShort())
        }
    }

    private fun processInt24(input: ByteBuffer, output: ByteBuffer) {
        val linearGain = AudioOutputRuntime.linearGain
        while (input.remaining() >= 3) {
            val low = input.get().toInt() and 0xFF
            val middle = input.get().toInt() and 0xFF
            val high = input.get().toInt()
            val value = low or (middle shl 8) or (high shl 16)
            val scaled = (value * linearGain)
                .roundToInt()
                .coerceIn(-8_388_608, 8_388_607)
            output.put((scaled and 0xFF).toByte())
            output.put((scaled shr 8 and 0xFF).toByte())
            output.put((scaled shr 16 and 0xFF).toByte())
        }
    }

    private fun processInt32(input: ByteBuffer, output: ByteBuffer) {
        val linearGain = AudioOutputRuntime.linearGain
        while (input.remaining() >= Int.SIZE_BYTES) {
            val scaled = (input.int.toDouble() * linearGain)
                .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                .toInt()
            output.putInt(scaled)
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        val linearGain = AudioOutputRuntime.linearGain
        while (input.remaining() >= Float.SIZE_BYTES) {
            output.putFloat((input.float * linearGain).coerceIn(-1f, 1f))
        }
    }
}
