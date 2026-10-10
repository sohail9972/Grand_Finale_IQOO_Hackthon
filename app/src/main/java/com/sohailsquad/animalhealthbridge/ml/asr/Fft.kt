package com.sohailsquad.animalhealthbridge.ml.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val size = re.size
        require(size > 0 && size and (size - 1) == 0) { "FFT length must be a power of two." }
        require(im.size == size) { "Real and imaginary arrays must have equal lengths." }
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val real = re[index]
                re[index] = re[reversed]
                re[reversed] = real
                val imaginary = im[index]
                im[index] = im[reversed]
                im[reversed] = imaginary
            }
        }
        var blockSize = 2
        while (blockSize <= size) {
            val angle = -2.0 * PI / blockSize
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            val half = blockSize / 2
            var blockStart = 0
            while (blockStart < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until half) {
                    val lower = blockStart + offset
                    val upper = lower + half
                    val productReal = twiddleReal * re[upper] - twiddleImaginary * im[upper]
                    val productImaginary = twiddleReal * im[upper] + twiddleImaginary * re[upper]
                    val lowerReal = re[lower]
                    val lowerImaginary = im[lower]
                    re[lower] = (lowerReal + productReal).toFloat()
                    im[lower] = (lowerImaginary + productImaginary).toFloat()
                    re[upper] = (lowerReal - productReal).toFloat()
                    im[upper] = (lowerImaginary - productImaginary).toFloat()
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                blockStart += blockSize
            }
            if (blockSize == size) break
            blockSize = blockSize shl 1
        }
    }
}
