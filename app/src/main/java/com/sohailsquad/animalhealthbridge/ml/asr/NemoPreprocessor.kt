package com.sohailsquad.animalhealthbridge.ml.asr

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

class NemoPreprocessor {
    private val window = FloatArray(MelFilterbank.N_FFT).apply {
        val offset = (size - WIN_LENGTH) / 2
        for (index in 0 until WIN_LENGTH) {
            this[offset + index] = (0.5 - 0.5 * cos(2.0 * PI * index / (WIN_LENGTH - 1))).toFloat()
        }
    }

    fun preprocess(samples: FloatArray): Array<FloatArray> {
        require(samples.isNotEmpty()) { "No PCM samples to preprocess." }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Expected finite normalized PCM samples." }
        val emphasized = FloatArray(samples.size)
        emphasized[0] = samples[0]
        for (index in 1 until samples.size) emphasized[index] = samples[index] - PREEMPHASIS * samples[index - 1]
        Log.d("AhbAsr", "Preemphasis: samples=${samples.size} coefficient=$PREEMPHASIS")

        val padding = MelFilterbank.N_FFT / 2
        val padded = FloatArray(emphasized.size + 2 * padding) { index ->
            emphasized[reflectIndex(index - padding, emphasized.size)]
        }
        val frameCount = 1 + (padded.size - MelFilterbank.N_FFT) / HOP_LENGTH
        val features = Array(MelFilterbank.N_MELS) { FloatArray(frameCount) }
        val real = FloatArray(MelFilterbank.N_FFT)
        val imaginary = FloatArray(MelFilterbank.N_FFT)
        val power = FloatArray(MelFilterbank.N_FFT / 2 + 1)
        val filters = MelFilterbank.matrix
        Log.d("AhbAsr", "Reflect padding=$padding; STFT: fft=512 hann=$WIN_LENGTH hop=$HOP_LENGTH frames=$frameCount")
        Log.d("AhbAsr", "Power spectrum -> Slaney mel filters=80; log guard=$LOG_ZERO_GUARD")
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * HOP_LENGTH
            for (index in real.indices) real[index] = padded[start + index] * window[index]
            imaginary.fill(0f)
            Fft.transform(real, imaginary)
            for (bin in power.indices) power[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            for (melIndex in features.indices) {
                var energy = 0.0
                for (bin in power.indices) energy += power[bin].toDouble() * filters[melIndex][bin]
                features[melIndex][frameIndex] = ln(energy + LOG_ZERO_GUARD).toFloat()
            }
        }
        for (feature in features) {
            var sum = 0.0
            for (value in feature) sum += value
            val mean = sum / frameCount
            var squaredDeviations = 0.0
            for (value in feature) {
                val deviation = value - mean
                squaredDeviations += deviation * deviation
            }
            val standardDeviation = if (frameCount > 1) sqrt(squaredDeviations / (frameCount - 1)) else 0.0
            for (frameIndex in feature.indices) {
                feature[frameIndex] = ((feature[frameIndex] - mean) / (standardDeviation + NORMALIZE_EPSILON)).toFloat()
            }
        }
        Log.d("AhbAsr", "Per-feature normalization complete: 80 x $frameCount epsilon=$NORMALIZE_EPSILON")
        return features
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        if (size == 1) return 0
        val period = 2 * (size - 1)
        val wrapped = ((index % period) + period) % period
        return if (wrapped < size) wrapped else period - wrapped
    }

    private companion object {
        const val WIN_LENGTH = 400
        const val HOP_LENGTH = 160
        const val PREEMPHASIS = 0.97f
        const val LOG_ZERO_GUARD = 5.960464477539063e-8
        const val NORMALIZE_EPSILON = 1e-5
    }
}
