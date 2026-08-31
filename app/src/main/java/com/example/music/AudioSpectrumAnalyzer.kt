package com.example.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Computes normalized magnitude bands from decoded PCM audio samples. */
object AudioSpectrumAnalyzer {
    fun fftBands(samples: ShortArray, bandCount: Int = 80): FloatArray {
        if (samples.isEmpty() || bandCount <= 0) return FloatArray(0)
        val n = Integer.highestOneBit(samples.size).coerceAtLeast(2)
        val real = DoubleArray(n) { index ->
            val hann = 0.5 - 0.5 * cos(2.0 * PI * index / (n - 1).coerceAtLeast(1))
            samples[index].toDouble() / Short.MAX_VALUE * hann
        }
        val imag = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t }
        }
        var size = 2
        while (size <= n) {
            val angle = -2.0 * PI / size
            val wr = cos(angle); val wi = sin(angle)
            for (start in 0 until n step size) {
                var ur = 1.0; var ui = 0.0
                val half = size / 2
                for (k in 0 until half) {
                    val p = start + k; val q = p + half
                    val tr = real[q] * ur - imag[q] * ui
                    val ti = real[q] * ui + imag[q] * ur
                    real[q] = real[p] - tr; imag[q] = imag[p] - ti
                    real[p] += tr; imag[p] += ti
                    val next = ur * wr - ui * wi
                    ui = ur * wi + ui * wr; ur = next
                }
            }
            size = size shl 1
        }
        val result = FloatArray(bandCount)
        for (band in result.indices) {
            val usable = n / 2 - 1
            val t0 = band.toDouble() / bandCount
            val t1 = (band + 1).toDouble() / bandCount
            val from = (1 + usable * t0 * t0).toInt().coerceIn(1, usable)
            val to = (1 + usable * t1 * t1).toInt().coerceIn(from + 1, n / 2)
            var sum = 0.0
            for (i in from until to) sum += sqrt(real[i] * real[i] + imag[i] * imag[i])
            val magnitude = sum / (to - from) / n
            result[band] = (ln(1.0 + magnitude * 640.0) / ln(641.0))
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
        return result
    }
}
