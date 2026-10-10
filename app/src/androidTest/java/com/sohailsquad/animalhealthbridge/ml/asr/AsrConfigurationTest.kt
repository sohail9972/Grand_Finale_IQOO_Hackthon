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
        assertEquals(setOf("kn", "ta", "te", "ml", "mr", "bn", "gu", "pa"), AsrConfig.LANGUAGE_CODES)
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        AsrConfig.LANGUAGE_CODES.forEach { language ->
            val directory = File(base, "models/indicconformer/$language")
            assertEquals(File(directory, "model.int8.onnx"), AsrConfig.model(context, language))
            assertEquals(File(directory, "tokens.txt"), AsrConfig.tokens(context, language))
        }
        assertEquals(AsrConfig.model(context, "kn"), AsrConfig.model(context))
    }

    @Test
    fun missingFilesAndUnsupportedLanguagesFailWithoutMicrophoneOrLeakedSessionLock() = runBlocking {
        val missing = File(context.cacheDir, "missing-asr-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = missing
        }
        val engine = AsrEngine(isolated)
        try {
            assertFalse(engine.isAvailable("kn"))
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
        } finally { engine.close() }
    }
}
