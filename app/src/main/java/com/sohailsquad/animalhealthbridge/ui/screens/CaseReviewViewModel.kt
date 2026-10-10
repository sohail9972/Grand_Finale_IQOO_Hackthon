package com.sohailsquad.animalhealthbridge.ui.screens

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.data.repository.CaseRepository
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import com.sohailsquad.animalhealthbridge.ml.translation.Translator
import com.sohailsquad.animalhealthbridge.triage.TriageEngine
import com.sohailsquad.animalhealthbridge.triage.TriageResult
import com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface CaseReviewState {
    data object Idle : CaseReviewState
    data object LoadingModel : CaseReviewState
    data object Structuring : CaseReviewState
    data class Success(val parsed: StructuredCase, val elapsedMs: Long?, val usedFallback: Boolean,
        val triageResult: TriageResult) : CaseReviewState
    data class Error(val msg: String) : CaseReviewState
}

class CaseReviewViewModel internal constructor(
    private val caseId: Long,
    private val repository: CaseRepository,
    private val engine: LlmEngine,
    private val translator: Translator,
    private val triageEngine: TriageEngine,
) : ViewModel() {
    constructor(context: Context, caseId: Long) : this(
        caseId, context.app().caseRepository, context.app().llmEngine,
        context.app().translator, context.app().triageEngine,
    )

    private val mutableState = MutableStateFlow<CaseReviewState>(CaseReviewState.Idle)
    val state = mutableState.asStateFlow()
    private val mutableRecord = MutableStateFlow<Case?>(null)
    val record = mutableRecord.asStateFlow()
    private val mutableModelAvailable = MutableStateFlow(false)
    val modelAvailable = mutableModelAvailable.asStateFlow()
    private val processingLock = Mutex()

    suspend fun processTranscript(transcript: String): Unit = processingLock.withLock {
        try {
            val existing = repository.get(caseId)
            mutableRecord.value = existing
            val input = transcript.ifBlank { existing.originalTranscript }
            require(input.isNotBlank()) { "Enter symptoms before reviewing." }
            require(input.trim() == existing.originalTranscript.trim()) {
                "The transcript does not match the saved case. Reopen the case to retry."
            }
            if (mutableState.value is CaseReviewState.Success && existing.processingComplete) return@withLock

            mutableState.value = CaseReviewState.LoadingModel
            Log.i(TAG, "Loading model for case=$caseId")
            engine.load()
            currentCoroutineContext().ensureActive()
            mutableModelAvailable.value = engine.isAvailable()
            Log.i(TAG, "Model available=${mutableModelAvailable.value} case=$caseId")

            if (existing.processingComplete) {
                val parsed = StructuredCase(
                    chiefComplaint = existing.chiefComplaint,
                    duration = existing.duration,
                    affectedArea = existing.affectedArea,
                    severityIndicators = Json.decodeFromString<List<String>>(existing.severityIndicatorsJson),
                    redFlags = Json.decodeFromString<List<String>>(existing.redFlagsJson),
                    additionalNotes = existing.additionalNotes,
                    confidence = existing.confidence,
                )
                val triageResult = TriageResult(resolveTriageLevel(existing.triageLevel), existing.triageRuleId,
                    triageEngine.reasonForRule(existing.triageRuleId))
                mutableState.value = CaseReviewState.Success(parsed, elapsedMs = null,
                    usedFallback = existing.structuredBy == Case.STRUCTURED_BY_FALLBACK, triageResult = triageResult)
                Log.i("AhbTriage", "Restored case=$caseId rule=${triageResult.matchedRuleId} level=${triageResult.level}")
                Log.i(TAG, "Reused saved analysis case=$caseId")
                return@withLock
            }

            mutableState.value = CaseReviewState.Structuring
            val translation = translator.translateResult(input, existing.sourceLang)
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            val result = engine.structure(translation.text)
            currentCoroutineContext().ensureActive()
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val usedFallback = result !is LlmEngine.Result.Success || result.usedFallback
            var parsed = when (result) {
                is LlmEngine.Result.Success -> result.case
                is LlmEngine.Result.Escalated -> result.case.copy(
                    confidence = StructuredCase.CONFIDENCE_LOW, additionalNotes = result.reason)
            }
            if (translation.isFallback) parsed = parsed.copy(confidence = StructuredCase.CONFIDENCE_LOW)
            val triageResult = withContext(Dispatchers.Default) { triageEngine.evaluate(parsed) }
            Log.i("AhbTriage", "case=$caseId rule=${triageResult.matchedRuleId} level=${triageResult.level}")
            mutableRecord.value = repository.saveStructured(caseId, parsed,
                if (translation.isFallback) "" else translation.text, triageResult,
                usedFallback, translation.isFallback)
            mutableState.value = when (result) {
                is LlmEngine.Result.Success -> CaseReviewState.Success(parsed, elapsedMs, usedFallback, triageResult)
                is LlmEngine.Result.Escalated -> CaseReviewState.Error(
                    "${result.reason}. A conservative case is saved; share it with a veterinarian.")
            }
            Log.i(TAG, "Analysis complete case=$caseId elapsedMs=$elapsedMs usedFallback=$usedFallback")
        } catch (failure: CancellationException) {
            mutableState.value = CaseReviewState.Idle
            throw failure
        } catch (failure: Throwable) {
            Log.e(TAG, "Case analysis failed case=$caseId", failure)
            mutableState.value = CaseReviewState.Error(
                failure.message ?: "Could not analyse this case. The draft is saved; retry.")
        }
    }

    private companion object {
        const val TAG = "AhbCaseReview"
    }
}
