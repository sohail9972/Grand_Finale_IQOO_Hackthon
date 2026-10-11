package com.sohailsquad.animalhealthbridge

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import com.sohailsquad.animalhealthbridge.data.repository.CaseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VetQueryFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun createCase(database: AppDatabase): Long {
        val animalId = database.animalDao().insert(AnimalProfile(tagNumber = UUID.randomUUID().toString(),
            species = "cattle", age = "", ownerName = "Test owner"))
        return database.caseDao().insert(Case(animalId = animalId, chiefComplaint = "Test observation",
            duration = "today", affectedArea = "not stated", confidence = "low", triageLevel = "SEE_VET_TODAY",
            triageRuleId = "R5", originalTranscript = "Test observation", englishTranscript = "Test observation"))
    }

    private fun query(caseId: Long, type: String) = VetQuery(caseId = caseId, vetId = "TEST-ONLY",
        vetName = "Test reviewer", questionTextEnglish = "Test $type question", questionTextNative = "Test $type question",
        nativeLanguage = "en", queryType = type)

    @Test
    fun allQuestionTypesPersistAnswersAndResumeReview() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val caseId = createCase(database)
            val repository = CaseRepository(context, database)
            val dao = database.vetQueryDao()
            listOf("PHOTO", "VIDEO", "YES_NO", "TEXT").forEach { repository.ask(query(caseId, it)) }
            assertEquals("AWAITING_INFORMATION", repository.get(caseId).status)
            assertEquals(4, dao.countPendingForCase(caseId))
            val questions = dao.observeForCase(caseId).first()
            for (question in questions) {
                val media = if (question.queryType in setOf("PHOTO", "VIDEO")) {
                    val mime = if (question.queryType == "VIDEO") "video/mp4" else "image/jpeg"
                    val uri = "file:///test-${UUID.randomUUID()}.${if (mime == "video/mp4") "mp4" else "jpg"}"
                    assertTrue(runCatching { repository.answer(caseId, question.id, "", listOf(uri)) }.isFailure)
                    repository.addEvidence(caseId, uri, mime)
                    listOf(uri)
                } else emptyList()
                val text = when (question.queryType) {
                    "YES_NO" -> "Not sure"
                    "TEXT" -> "Test typed or dictated response"
                    else -> ""
                }
                repository.answer(caseId, question.id, text, media)
                val answered = requireNotNull(dao.getById(question.id))
                assertEquals("ANSWERED", answered.status)
                assertEquals(text, answered.answerText)
                assertEquals(media, Json.decodeFromString<List<String>>(answered.answerMediaUris))
                assertNotNull(answered.answeredAt)
            }
            assertEquals(0, dao.countPendingForCase(caseId))
            assertEquals("REVIEW_REQUESTED", repository.get(caseId).status)
            assertEquals(2, database.evidenceDao().countForCase(caseId))
        } finally { database.close() }
    }

    @Test
    fun daoAnswerHelperSerializesMediaAndOnlyAnswersPendingQueries() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val caseId = createCase(database)
            val dao = database.vetQueryDao()
            val photoId = dao.insert(query(caseId, "PHOTO"))
            val textId = dao.insert(query(caseId, "TEXT"))
            val uri = "content://test/photo?caption=\"animal\""
            assertEquals(2, dao.countPendingForCase(caseId))
            assertEquals(1, dao.updateAnswer(photoId, null, uri, 100L))
            assertEquals(0, dao.updateAnswer(photoId, "overwrite", null, 200L))
            val photo = requireNotNull(dao.getById(photoId))
            assertEquals("", photo.answerText)
            assertEquals(listOf(uri), Json.decodeFromString<List<String>>(photo.answerMediaUris))
            assertEquals(100L, photo.answeredAt)
            assertEquals(1, dao.updateAnswer(textId, "Typed response", null, 300L))
            assertEquals("[]", dao.getById(textId)?.answerMediaUris)
            assertEquals(0, dao.pendingCount(caseId))
        } finally { database.close() }
    }

    @Test
    fun fileProviderSharesEvidenceWithoutExposingOtherInternalFiles() {
        val root = File(context.filesDir, "evidence").apply { mkdirs() }
        val evidence = File.createTempFile("test_capture_", ".jpg", root)
        val unrelated = File.createTempFile("test_private_", ".txt", context.filesDir)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", evidence)
            assertEquals("content", uri.scheme)
            assertTrue(runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", unrelated)
            }.isFailure)
        } finally {
            evidence.delete()
            unrelated.delete()
        }
    }
}
