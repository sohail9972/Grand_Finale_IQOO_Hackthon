package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AsrConfigurationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachLanguageResolvesItsInt8ModelAndSharedVocabularyFile() {
        assertEquals(setOf("en", "hi", "kn", "ta", "te", "ml", "mr", "bn", "gu", "pa"), AsrConfig.LANGUAGE_CODES)
        val base = context.filesDir
        (AsrConfig.LANGUAGE_CODES - "en").forEach { language ->
            val directory = File(base, "models/indicconformer/$language")
            assertEquals(File(directory, "model.int8.onnx"), AsrConfig.model(context, language))
            assertEquals(File(directory, "tokens.txt"), AsrConfig.tokens(context, language))
        }
        assertEquals(AsrConfig.model(context, "kn"), AsrConfig.model(context))
    }

    @Test
    fun englishResolvesWhisperFilesInInternalStorage() {
        val directory = File(context.filesDir, "models/whisper-tiny-en")
        assertEquals("whisper-tiny-en", AsrConfig.ENGLISH_MODEL_DIR)
        assertEquals(File(directory, "tiny.en-encoder.onnx"), AsrConfig.whisperEncoder(context))
        assertEquals(File(directory, "tiny.en-decoder.onnx"), AsrConfig.whisperDecoder(context))
        assertEquals(File(directory, "tiny.en-tokens.txt"), AsrConfig.whisperTokens(context))
        assertTrue(runCatching { AsrConfig.model(context, "en") }.isFailure)
    }

    @Test
    fun englishResolvesMoonshineFilesInInternalStorage() {
        val directory = File(context.filesDir, "models/moonshine-tiny-en")
        assertEquals("moonshine-tiny-en", AsrConfig.MOONSHINE_DIR)
        assertEquals(File(directory, "preprocess.onnx"), AsrConfig.moonshinePreprocess(context))
        assertEquals(File(directory, "encode.int8.onnx"), AsrConfig.moonshineEncode(context))
        assertEquals(File(directory, "uncached_decode.int8.onnx"), AsrConfig.moonshineUncachedDecode(context))
        assertEquals(File(directory, "cached_decode.int8.onnx"), AsrConfig.moonshineCachedDecode(context))
        assertEquals(File(directory, "tokens.txt"), AsrConfig.moonshineTokens(context))
    }

    @Test
    fun englishAvailabilityRequiresFiveNonemptyFiles() {
        val root = File(context.cacheDir, "moonshine-check-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val engine = AsrEngine(isolated)
        val files = listOf(AsrConfig.moonshinePreprocess(isolated), AsrConfig.moonshineEncode(isolated),
            AsrConfig.moonshineUncachedDecode(isolated), AsrConfig.moonshineCachedDecode(isolated), AsrConfig.moonshineTokens(isolated))
        try {
            assertFalse(engine.isAvailable("en"))
            assertFalse(AsrConfig.isMoonshineAvailable(isolated))
            requireNotNull(files.first().parentFile).mkdirs()
            files.dropLast(1).forEach { file ->
                file.writeText("availability-check-only")
                assertFalse(engine.isAvailable("en"))
            }
            files.last().writeText("availability-check-only")
            assertTrue(engine.isAvailable("en"))
            assertTrue(AsrConfig.isMoonshineAvailable(isolated))
            files.last().writeBytes(byteArrayOf())
            assertFalse(engine.isAvailable("en"))
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test
    fun missingFilesAndUnsupportedLanguagesFailWithoutMicrophoneOrLeakedSessionLock() = runBlocking {
        val missing = File(context.cacheDir, "missing-asr-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = missing
            override fun getExternalFilesDir(type: String?): File = missing
        }
        val engine = AsrEngine(isolated)
        try {
            assertFalse(engine.isAvailable("kn"))
            assertFalse(engine.isAvailable("en"))
            assertFalse(engine.isAvailable("../kn"))
            repeat(2) {
                val result = engine.startListening(
                    onPartial = { error("No partial result expected without model files") },
                    onFinal = { error("No final result expected without model files") },
                    language = "kn",
                )
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("files unavailable"))
            }
            assertTrue(engine.listen(language = "kn").isFailure)
            assertTrue(engine.listen(language = "hi").isFailure)
            repeat(2) {
                val result = engine.listen(language = "en")
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Moonshine English files unavailable"))
            }
        } finally { engine.close() }
    }
}
