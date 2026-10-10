package com.sohailsquad.animalhealthbridge.ml.llm

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmEngineDeviceTest {
    @Test
    fun loadAndStructureWithoutFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transcript = InstrumentationRegistry.getArguments().getString("transcript")
            ?: "The cow has a swollen left leg and has been limping since yesterday."
        val engine = LlmEngine(context)
        try {
            withTimeout(180_000L) {
                val started = SystemClock.elapsedRealtime()
                val loaded = engine.load()
                Log.i("AhbModelTest", "Load: available=$loaded elapsedMs=${SystemClock.elapsedRealtime() - started}")
                assertTrue("Model loading failed; inspect AhbLlm and GenieXSdk logs", loaded)
                assertTrue(engine.isAvailable())

                val inferenceStarted = SystemClock.elapsedRealtime()
                val result = engine.structure(transcript)
                Log.i("AhbModelTest", "Inference: elapsedMs=${SystemClock.elapsedRealtime() - inferenceStarted} result=$result")
                assertTrue("Model did not return a structured case: $result", result is LlmEngine.Result.Success)
                val success = result as LlmEngine.Result.Success
                assertFalse("Keyword fallback does not count as model inference", success.usedFallback)
                assertTrue("Model returned an empty complaint", success.case.chiefComplaint.isNotBlank())
            }
        } finally {
            engine.close()
        }
    }
}
