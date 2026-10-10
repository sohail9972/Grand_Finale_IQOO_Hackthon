package com.sohailsquad.animalhealthbridge.ml.asr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object MelFilterbank {
    const val N_MELS = 80
    const val N_FFT = 512
    const val SAMPLE_RATE = 16000
    const val FMIN = 0f
    const val FMAX = 8000f

    val matrix: Array<FloatArray> by lazy { compute() }

    private fun hzToMel(hz: Float): Float =
        if (hz < 1000f) hz * 3f / 200f
        else 15f + 14.545078f * ln(hz / 1000f)

    private fun melToHz(mel: Float): Float =
        if (mel < 15f) mel * 200f / 3f
        else 1000f * exp((mel - 15f) / 14.545078f)

    private fun compute(): Array<FloatArray> {
        val lowerMel = hzToMel(FMIN)
        val upperMel = hzToMel(FMAX)
        val frequencies = FloatArray(N_MELS + 2) { index ->
            melToHz(lowerMel + (upperMel - lowerMel) * index / (N_MELS + 1))
        }
        return Array(N_MELS) { melIndex ->
            val lower = frequencies[melIndex]
            val center = frequencies[melIndex + 1]
            val upper = frequencies[melIndex + 2]
            val normalization = 2f / (upper - lower)
            FloatArray(N_FFT / 2 + 1) { bin ->
                val frequency = bin * SAMPLE_RATE.toFloat() / N_FFT
                val rising = (frequency - lower) / (center - lower)
                val falling = (upper - frequency) / (upper - center)
                max(0f, min(rising, falling)) * normalization
            }
        }
    }
}
