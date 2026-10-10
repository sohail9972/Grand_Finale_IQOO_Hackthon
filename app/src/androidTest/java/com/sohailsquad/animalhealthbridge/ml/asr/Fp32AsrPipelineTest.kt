package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class Fp32AsrPipelineTest {
    @Test
    fun impulseFftHasUnitRealSpectrum() {
        val real = FloatArray(512).apply { this[0] = 1f }
        val imaginary = FloatArray(512)
        Fft.transform(real, imaginary)
        real.indices.forEach { index ->
            assertEquals(1f, real[index], 1e-6f)
            assertEquals(0f, imaginary[index], 1e-6f)
        }
    }

    @Test
    fun fftMatchesDirectComplexDft() {
        val realInput = floatArrayOf(0.1f, -0.3f, 0.8f, 0.7f, -0.1f, 0.2f, -0.6f, 0.4f)
        val imaginaryInput = FloatArray(realInput.size) { index -> index / 10f }
        val real = realInput.copyOf()
        val imaginary = imaginaryInput.copyOf()
        Fft.transform(real, imaginary)
        real.indices.forEach { frequency ->
            var expectedReal = 0.0
            var expectedImaginary = 0.0
            real.indices.forEach { sample ->
                val angle = -2.0 * PI * frequency * sample / real.size
                expectedReal += realInput[sample] * cos(angle) - imaginaryInput[sample] * sin(angle)
                expectedImaginary += realInput[sample] * sin(angle) + imaginaryInput[sample] * cos(angle)
            }
            assertEquals(expectedReal, real[frequency].toDouble(), 1e-5)
            assertEquals(expectedImaginary, imaginary[frequency].toDouble(), 1e-5)
        }
    }

    @Test
    fun fftRejectsInvalidArrayLengths() {
        assertThrows(IllegalArgumentException::class.java) { Fft.transform(FloatArray(0), FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { Fft.transform(FloatArray(3), FloatArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { Fft.transform(FloatArray(8), FloatArray(4)) }
    }

    @Test
    fun melFiltersHaveExpectedShapeAndFinitePositiveWeights() {
        assertEquals(80, MelFilterbank.matrix.size)
        MelFilterbank.matrix.forEach { filter ->
            assertEquals(257, filter.size)
            assertTrue(filter.all { it.isFinite() && it >= 0f })
            assertTrue(filter.any { it > 0f })
            assertEquals(0f, filter.first(), 0f)
        }
    }

    @Test
    fun silenceHasExpectedFrameCountAndZeroNormalizedFeatures() {
        val features = NemoPreprocessor().preprocess(FloatArray(16000))
        assertEquals(80, features.size)
        features.forEach { feature ->
            assertEquals(101, feature.size)
            assertTrue(feature.all { it == 0f })
        }
    }

    @Test
    fun normalizedFeaturesHaveZeroMeanAndUnitSampleDeviation() {
        val random = Random(37)
        val features = NemoPreprocessor().preprocess(FloatArray(3200) { random.nextFloat() * 2f - 1f })
        features.forEach { feature ->
            assertEquals(21, feature.size)
            assertTrue(feature.all { it.isFinite() })
            val mean = feature.sumOf { it.toDouble() } / feature.size
            val variance = feature.sumOf { (it - mean) * (it - mean) } / (feature.size - 1)
            assertEquals(0.0, mean, 1e-5)
            assertEquals(1.0, sqrt(variance), 1e-3)
        }
    }

    @Test
    fun preprocessingHandlesShortSignalsAndRejectsInvalidPcm() {
        val preprocessor = NemoPreprocessor()
        listOf(1, 2, 159, 160, 511).forEach { size ->
            val features = preprocessor.preprocess(FloatArray(size) { index -> sin(index.toDouble()).toFloat() })
            assertTrue(features.all { it.size == 1 + size / 160 && it.all(Float::isFinite) })
        }
        assertThrows(IllegalArgumentException::class.java) { preprocessor.preprocess(FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { preprocessor.preprocess(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { preprocessor.preprocess(floatArrayOf(1.1f)) }
    }

    @Test
    fun appendedBlankCollapsesRepeatsAndPreservesWordBoundaries() {
        val decoder = CtcDecoder(listOf("<unk>", "\u2581ka", "n", "\u2581ta"))
        assertEquals(4, decoder.blankIndexFor(5))
        assertEquals("ka kan ta", decoder.decode(scores(5, 1, 1, 4, 1, 2, 0, 3)))
        assertEquals("nn", decoder.decode(scores(5, 2, 4, 2)))
        assertEquals("", decoder.decode(scores(5, 4, 4, 0)))
    }

    @Test
    fun zeroBlankLayoutAndEmptyOutputAreSupported() {
        val decoder = CtcDecoder(listOf("<unk>", "\u2581ka", "n", "\u2581ta"))
        assertEquals(0, decoder.blankIndexFor(4))
        assertEquals("ka kan ta", decoder.decode(scores(4, 1, 1, 0, 1, 2, 3)))
        assertEquals("", decoder.decode(emptyArray()))
    }

    @Test
    fun decoderRejectsMalformedOutputsAndVocabulary() {
        val decoder = CtcDecoder(listOf("<unk>", "\u2581ka"))
        assertThrows(IllegalArgumentException::class.java) { CtcDecoder(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(scores(4, 1)) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(arrayOf(FloatArray(3), FloatArray(2))) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(arrayOf(floatArrayOf(0f, Float.NaN, 0f))) }
        assertThrows(IllegalArgumentException::class.java) { decoder.decode(arrayOf(FloatArray(3) { Float.NEGATIVE_INFINITY })) }
    }

    @Test
    fun availabilityPrefersInternalFp32FilesWithoutLoadingOrRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "fp32-asr-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val fp32 = Fp32AsrEngine(isolated)
        val engine = AsrEngine(isolated)
        try {
            assertFalse(fp32.isAvailable("kn"))
            assertFalse(fp32.load("kn"))
            assertFalse(engine.isAvailable("kn"))
            val directory = File(root, "models/indicconformer-fp32/kn")
            assertTrue(directory.mkdirs())
            val model = File(directory, "model.onnx")
            val vocab = File(directory, "vocab.json")
            model.writeText("availability fixture, not an ONNX model")
            assertFalse(fp32.isAvailable("kn"))
            vocab.writeText("[]")
            assertTrue(fp32.isAvailable("kn"))
            assertTrue(engine.isAvailable("kn"))
            assertFalse(engine.isAvailable("ta"))
            assertFalse(fp32.isAvailable("../kn"))
            assertFalse(engine.isAvailable("../kn"))
            vocab.writeText("")
            assertFalse(fp32.isAvailable("kn"))
        } finally {
            engine.close()
            fp32.release()
            root.deleteRecursively()
        }
    }

    private fun scores(width: Int, vararg tokens: Int): Array<FloatArray> =
        Array(tokens.size) { index -> FloatArray(width) { -10f }.apply { this[tokens[index]] = 0f } }
}
