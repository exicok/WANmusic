package com.example.music

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

object AudioOutputRuntime {
    private val gainBits = AtomicInteger(0f.toBits())

    val gainDb: Float
        get() = Float.fromBits(gainBits.get())

    val linearGain: Float
        get() = 10f.pow(gainDb / 20f)

    fun setGainDb(value: Float) {
        gainBits.set(
            value.coerceIn(
                AudioOutputConfig.MIN_GAIN_DB,
                AudioOutputConfig.MAX_GAIN_DB
            ).toBits()
        )
    }
}
