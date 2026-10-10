package com.sohailsquad.animalhealthbridge

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.data.repository.CaseRepository
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.translation.Translator
import com.sohailsquad.animalhealthbridge.triage.TriageEngine
import com.sohailsquad.animalhealthbridge.ui.screens.CaseReviewState
import com.sohailsquad.animalhealthbridge.ui.screens.CaseReviewViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaseReviewProcessingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingModelKeepsRedFlagsAndSavesConservativeTriage() = runBlocking {
        val transcript = "The cow cannot stand and has bleeding since yesterday."
        fixture(transcript).use { fixture ->
            fixture.model.processTranscript(transcript)
            val result = fixture.model.state.value as CaseReviewState.Success
            assertTrue(result.usedFallback)
            assertTrue(requireNotNull(result.elapsedMs) >= 0L)
            assertTrue(result.parsed.redFlags.contains("cannot stand"))
            assertFalse(fixture.model.modelAvailable.value)
            val record = fixture.repository.get(fixture.caseId)
            assertTrue(record.processingComplete)
            assertEquals("EMERGENCY_NOW", record.triageLevel)
            assertEquals(record.triageLevel, result.triageResult.level.name)
            assertEquals(record.triageRuleId, result.triageResult.matchedRuleId)
            assertTrue(result.triageResult.reason.isNotBlank())
            assertEquals(transcript, record.originalTranscript)
            assertEquals(result.parsed.chiefComplaint, record.chiefComplaint)
            val logs = fixture.database.triageLogDao()
            assertEquals("R1", logs.getByCaseId(fixture.caseId).single().matchedRuleId)
            assertEquals(1, logs.countByLevel("EMERGENCY_NOW"))
            assertEquals(0, logs.countByLevel("MONITOR"))
        }
    }

    @Test
    fun returningToSubmittedCaseReusesSavedFieldsWithoutNewTriage() = runBlocking {
        val transcript = "The cow has a swollen leg since yesterday."
        fixture(transcript).use { fixture ->
            fixture.model.processTranscript(transcript)
            fixture.repository.requestReview(fixture.caseId)
            val previous = fixture.repository.get(fixture.caseId)
            val reopened = fixture.newModel()
            reopened.processTranscript("")
            val result = reopened.state.value as CaseReviewState.Success
            assertNull(result.elapsedMs)
            assertTrue(result.usedFallback)
            assertEquals(previous.chiefComplaint, result.parsed.chiefComplaint)
            assertEquals(previous.triageLevel, result.triageResult.level.name)
            assertEquals(previous.triageRuleId, result.triageResult.matchedRuleId)
            assertTrue(result.triageResult.reason.isNotBlank())
            assertEquals(previous, fixture.repository.get(fixture.caseId))
            assertEquals(1, fixture.database.triageLogDao().getForCase(fixture.caseId).size)
        }
    }

    @Test
    fun duplicateProcessingDoesNotDuplicateSavedAnalysis() = runBlocking {
        val transcript = "The cow has swelling in the leg today."
        fixture(transcript).use { fixture ->
            coroutineScope {
                listOf(async { fixture.model.processTranscript(transcript) },
                    async { fixture.model.processTranscript(transcript) }).awaitAll()
            }
            assertTrue(fixture.model.state.value is CaseReviewState.Success)
            assertEquals(1, fixture.database.triageLogDao().getForCase(fixture.caseId).size)
        }
    }

    @Test
    fun blankMissingAndMismatchedCasesBecomeErrorsWithoutOverwriting() = runBlocking {
        fixture("").use { fixture ->
            fixture.model.processTranscript("")
            assertTrue(fixture.model.state.value is CaseReviewState.Error)
            assertFalse(fixture.repository.get(fixture.caseId).processingComplete)
        }
        fixture("Original observations").use { fixture ->
            fixture.model.processTranscript("Different observations")
            assertTrue(fixture.model.state.value is CaseReviewState.Error)
            assertEquals("Original observations", fixture.repository.get(fixture.caseId).originalTranscript)
            assertFalse(fixture.repository.get(fixture.caseId).processingComplete)
            val missing = fixture.newModel(Long.MAX_VALUE)
            missing.processTranscript("Original observations")
            assertTrue(missing.state.value is CaseReviewState.Error)
            assertNull(missing.record.value)
        }
    }

    @Test
    fun translationFailurePreservesOriginalAndLowConfidence() = runBlocking {
        val transcript = "Original observations"
        fixture(transcript, "zz").use { fixture ->
            fixture.model.processTranscript(transcript)
            val result = fixture.model.state.value as CaseReviewState.Success
            val record = fixture.repository.get(fixture.caseId)
            assertEquals("low", result.parsed.confidence)
            assertTrue(record.translationFallback)
            assertEquals(transcript, record.originalTranscript)
            assertEquals("", record.englishTranscript)
        }
    }

    private suspend fun fixture(transcript: String, language: String = "en"): Fixture {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val missingDirectory = File(context.cacheDir, "missing-review-model-" + UUID.randomUUID())
        val modelContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File = missingDirectory
        }
        val engine = LlmEngine(modelContext)
        try {
            val animalId = database.animalDao().insert(AnimalProfile(tagNumber = UUID.randomUUID().toString(),
                species = "cattle", age = "", ownerName = ""))
            val repository = CaseRepository(context, database)
            val caseId = repository.saveDraft(animalId, transcript, language, UUID.randomUUID().toString())
            return Fixture(database, repository, engine, caseId)
        } catch (failure: Throwable) {
            engine.close()
            database.close()
            throw failure
        }
    }

    private inner class Fixture(val database: AppDatabase, val repository: CaseRepository,
        private val engine: LlmEngine, val caseId: Long) : Closeable {
        val model = newModel()
        fun newModel(id: Long = caseId) = CaseReviewViewModel(id, repository, engine,
            Translator(context), TriageEngine(context))
        override fun close() {
            engine.close()
            database.close()
        }
    }
}
