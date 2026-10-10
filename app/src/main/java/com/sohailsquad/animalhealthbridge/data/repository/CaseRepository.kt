package com.sohailsquad.animalhealthbridge.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.*
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import com.sohailsquad.animalhealthbridge.triage.TriageResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaseRepository(context: Context, val database: AppDatabase = AppDatabase.get(context)) {
    private val cases = database.caseDao()
    suspend fun get(caseId: Long): Case = requireNotNull(cases.getById(caseId)) { "Case not found on this phone." }

    suspend fun saveDraft(animalId: Long, text: String, language: String, key: String): Long =
        database.withTransaction {
            requireNotNull(database.animalDao().getById(animalId)) { "Identify the animal first." }
            val existing = cases.getByDraftKey(key)
            if (existing != null) {
                if (existing.status == "DRAFT" && existing.originalTranscript != text) {
                    cases.update(existing.copy(originalTranscript = text, sourceLang = language, processingComplete = false))
                }
                return@withTransaction existing.id
            }
            val id = cases.insert(Case(animalId = animalId, chiefComplaint = text, duration = StructuredCase.NOT_STATED,
                affectedArea = StructuredCase.NOT_STATED, confidence = "low", triageLevel = "SEE_VET_TODAY",
                triageRuleId = "PENDING", originalTranscript = text, englishTranscript = "",
                draftKey = key, sourceLang = language, processingComplete = false, structuredBy = Case.STRUCTURED_BY_HUMAN))
            Log.i("AhbRecord", "Draft saved case=$id animal=$animalId")
            id
        }

    suspend fun saveStructured(caseId: Long, structured: StructuredCase, english: String,
        triage: TriageResult, modelFallback: Boolean, translationFallback: Boolean): Case =
        database.withTransaction {
            val previous = get(caseId)
            require(previous.status == "DRAFT") { "A submitted case cannot be overwritten." }
            val updated = previous.copy(chiefComplaint = structured.chiefComplaint, duration = structured.duration,
                affectedArea = structured.affectedArea, severityIndicatorsJson = Json.encodeToString(structured.severityIndicators),
                redFlagsJson = Json.encodeToString(structured.redFlags), additionalNotes = structured.additionalNotes,
                confidence = structured.confidence, englishTranscript = english, triageLevel = triage.level.name,
                triageRuleId = triage.matchedRuleId, processingComplete = true,
                translationFallback = translationFallback,
                structuredBy = if (modelFallback) Case.STRUCTURED_BY_FALLBACK else Case.STRUCTURED_BY_MODEL)
            cases.update(updated)
            database.triageLogDao().insert(TriageLog(caseId = caseId, matchedRuleId = triage.matchedRuleId,
                extractedFieldsJson = Json.encodeToString(structured), triageLevel = triage.level.name))
            Log.i("AhbRecord", "Structured case saved case=$caseId rule=${triage.matchedRuleId}")
            updated
        }

    suspend fun requestReview(caseId: Long) = database.withTransaction {
        val case = get(caseId)
        require(case.processingComplete) { "Wait for case preparation to finish." }
        if (case.status == "DRAFT") cases.update(case.copy(status = "REVIEW_REQUESTED"))
        Log.i("AhbVet", "Local review requested case=$caseId")
    }

    suspend fun addEvidence(caseId: Long, uri: String, mime: String) = database.withTransaction {
        require(get(caseId).status != "APPROVED") { "An approved case is read-only." }
        database.evidenceDao().insert(Evidence(caseId = caseId, uri = uri, mimeType = mime))
        Log.i("AhbCamera", "Evidence attached case=$caseId type=$mime")
    }

    suspend fun ask(query: VetQuery) = database.withTransaction {
        require(query.vetName.isNotBlank() && query.vetId.isNotBlank() && query.questionTextEnglish.isNotBlank())
        require(query.queryType in setOf("PHOTO", "VIDEO", "YES_NO", "TEXT"))
        require(get(query.caseId).processingComplete && database.treatmentDao().getForCase(query.caseId) == null) {
            "Questions cannot be added to an approved or incomplete case."
        }
        database.vetQueryDao().insert(query)
        val case = get(query.caseId)
        database.caseDao().update(case.copy(status = "AWAITING_INFORMATION"))
        Log.i("AhbVet", "Question saved case=${query.caseId} type=${query.queryType}")
    }

    suspend fun answer(caseId: Long, queryId: Long, text: String, media: List<String>) = database.withTransaction {
        val query = requireNotNull(database.vetQueryDao().getById(queryId))
        require(query.caseId == caseId && query.status == "PENDING")
        require(text.isNotBlank() || media.isNotEmpty()) { "Answer in text or attach evidence." }
        require(media.size <= 5)
        val known = database.evidenceDao().getForCase(caseId).map { it.uri }
        require(media.all { it in known }) { "Attachment does not belong to this case." }
        database.vetQueryDao().answer(queryId, text.trim(), Json.encodeToString(media), System.currentTimeMillis())
        if (database.vetQueryDao().pendingCount(caseId) == 0) {
            database.caseDao().update(get(caseId).copy(status = "REVIEW_REQUESTED"))
        }
        Log.i("AhbVet", "Question answered case=$caseId query=$queryId")
    }
}
