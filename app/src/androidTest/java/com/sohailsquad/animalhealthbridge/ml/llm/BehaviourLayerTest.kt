package com.sohailsquad.animalhealthbridge.ml.llm

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.data.repository.CaseRepository
import com.sohailsquad.animalhealthbridge.ml.translation.Translator
import com.sohailsquad.animalhealthbridge.ui.screens.BehaviourState
import com.sohailsquad.animalhealthbridge.ui.screens.BehaviourViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BehaviourLayerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }
    private val fields = BehaviourFields("Pacing near the gate", listOf("Seeking attention",
        "A change in routine", "Environmental stress"), "low", "Observe the surroundings.", "medium")

    @Test
    fun parserAcceptsValidAndFencedJsonWithoutPersistingFallbackMetadata() {
        val raw = json.encodeToString(fields)
        assertEquals(fields, parseBehaviourJson(raw))
        assertEquals(fields, parseBehaviourJson("```json\n$raw\n```"))
        assertFalse(json.encodeToString(fields.copy(usedFallback = true)).contains("usedFallback"))
    }

    @Test
    fun parserRejectsIncompleteMalformedAndUnsafeEnumValues() {
        assertNull(parseBehaviourJson(""))
        assertNull(parseBehaviourJson("{}"))
        assertNull(parseBehaviourJson("not JSON"))
        assertNull(parseBehaviourJson(json.encodeToString(fields.copy(urgency = "unknown"))))
        assertNull(parseBehaviourJson(json.encodeToString(fields.copy(confidence = "unknown"))))
        assertNull(parseBehaviourJson(json.encodeToString(fields.copy(possible_causes = emptyList()))))
    }

    @Test
    fun contextRaisesUrgencyWithoutDowngradingModelOrEmergency() {
        assertEquals("low", fields.withContextSafety("hours", "Yes", "Pacing").urgency)
        assertEquals("medium", fields.withContextSafety("week+", "Yes", "Pacing").urgency)
        assertEquals("medium", fields.withContextSafety("two months", "Not sure", "Pacing").urgency)
        assertEquals("high", fields.withContextSafety("hours", "No", "Pacing").urgency)
        assertEquals("high", fields.withContextSafety("hours", "Not sure", "Not drinking").urgency)
        assertEquals("high", fields.withContextSafety("hours", "Yes", "Pacing", emergency = true).urgency)
        assertEquals("high", fields.copy(urgency = "high").withContextSafety("hours", "Yes", "Pacing").urgency)
        assertEquals("medium", fields.copy(usedFallback = true).withContextSafety("hours", "Yes", "Pacing").urgency)
    }

    @Test
    fun missingModelIsHonestAndConservativeAndBlankInputFailsSafely() = runBlocking {
        val engine = missingModelEngine()
        try {
            val fallback = engine.structureBehaviour("Pacing", "hours", "Yes").getOrThrow()
            assertTrue(fallback.usedFallback)
            assertEquals("Pacing", fallback.behaviour_summary)
            assertTrue(fallback.possible_causes.isEmpty())
            assertEquals("low", fallback.confidence)
            assertEquals("medium", fallback.urgency)
            assertEquals("high", engine.structureBehaviour("Cannot stand", "hours", "Yes").getOrThrow().urgency)
            assertEquals("high", engine.structureBehaviour("Pacing", "hours", "No").getOrThrow().urgency)
            assertTrue(engine.structureBehaviour("", "hours", "Yes").isFailure)
        } finally { engine.close() }
    }

    @Test
    fun stateRestoresAndVetHandoffUsesRealDeduplicatedDrafts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val engine = missingModelEngine()
        try {
            val animalId = database.animalDao().insert(AnimalProfile(tagNumber = UUID.randomUUID().toString(),
                species = "cattle", age = "", ownerName = ""))
            val repository = CaseRepository(context, database)
            withContext(Dispatchers.Main) {
                val handle = SavedStateHandle()
                val model = BehaviourViewModel(animalId, handle, engine, Translator(context), repository, { "en" })
                model.analyze("Pacing near the gate", "week+", "Yes")
                val success = model.state.value as BehaviourState.Success
                assertTrue(success.usedFallback)
                assertEquals("medium", success.fields.urgency)
                val restored = BehaviourViewModel(animalId, handle, engine, Translator(context), repository, { "en" })
                assertEquals(success, restored.state.value)
                val first = restored.createVetCase().getOrThrow()
                val second = restored.createVetCase().getOrThrow()
                assertEquals(first, second)
                val record = repository.get(first.first)
                assertEquals(animalId, record.animalId)
                assertEquals(first.second, record.originalTranscript)
                assertFalse(record.processingComplete)
                assertEquals("DRAFT", record.status)
                assertFalse(record.originalTranscript.contains("Environmental stress"))
                restored.setDescription("A different observation")
                assertEquals(BehaviourState.Idle, restored.state.value)
                assertNotEquals(first.first, restored.createVetCase().getOrThrow().first)
                val invalid = BehaviourViewModel(0L, SavedStateHandle(), engine, Translator(context), repository, { "en" })
                invalid.analyze("Pacing", "hours", "Yes")
                assertTrue(invalid.state.value is BehaviourState.Error)
            }
        } finally {
            engine.close()
            database.close()
        }
    }

    private fun missingModelEngine(): LlmEngine {
        val missing = File(context.cacheDir, "missing-behaviour-model-${UUID.randomUUID()}")
        return LlmEngine(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = missing
        })
    }
}
