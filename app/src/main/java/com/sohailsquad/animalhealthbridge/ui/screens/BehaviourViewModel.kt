package com.sohailsquad.animalhealthbridge.ui.screens

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.repository.CaseRepository
import com.sohailsquad.animalhealthbridge.ml.llm.BehaviourFields
import com.sohailsquad.animalhealthbridge.ml.llm.LlmEngine
import com.sohailsquad.animalhealthbridge.ml.llm.withContextSafety
import com.sohailsquad.animalhealthbridge.ml.translation.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

sealed interface BehaviourState {
    data object Idle : BehaviourState
    data object Loading : BehaviourState
    data class Success(val fields: BehaviourFields, val elapsedMs: Long, val usedFallback: Boolean) : BehaviourState
    data class Error(val msg: String) : BehaviourState
}

class BehaviourViewModel internal constructor(
    private val animalId: Long,
    private val savedState: SavedStateHandle,
    private val engine: LlmEngine,
    private val translator: Translator,
    private val repository: CaseRepository,
    private val selectedLanguage: suspend () -> String,
) : ViewModel() {
    constructor(context: Context, animalId: Long, savedState: SavedStateHandle) : this(
        animalId, savedState, context.app().llmEngine, context.app().translator,
        context.app().caseRepository, { context.applicationContext.app().preferences.selectedLanguage() },
    )
    private val processingLock = Mutex()
    val description = savedState.getStateFlow("description", "")
    val duration = savedState.getStateFlow("duration", "")
    val eating = savedState.getStateFlow("eating", "Not sure")
    private val mutableState = MutableStateFlow<BehaviourState>(restoreState())
    val state = mutableState.asStateFlow()
    private var draftKey = savedState.get<String>("draftKey") ?: UUID.randomUUID().toString().also {
        savedState["draftKey"] = it
    }

    fun setDescription(value: String) = updateInput("description", value)
    fun setDuration(value: String) = updateInput("duration", value)
    fun setEating(value: String) = updateInput("eating", value)

    private fun updateInput(key: String, value: String) {
        if (mutableState.value == BehaviourState.Loading || savedState.get<String>(key) == value) return
        savedState[key] = value
        savedState.remove<String>("result")
        if (savedState.remove<Long>("createdCaseId") != null) {
            draftKey = UUID.randomUUID().toString()
            savedState["draftKey"] = draftKey
        }
        mutableState.value = BehaviourState.Idle
    }

    suspend fun analyze(description: String, duration: String, eating: String): Unit = processingLock.withLock {
        if (mutableState.value is BehaviourState.Success && description == this.description.value &&
            duration == this.duration.value && eating == this.eating.value) return@withLock
        try {
            require(animalId > 0) { "Identify the animal first." }
            require(description.isNotBlank() && duration.isNotBlank()) { "Enter a description and choose a duration." }
            setDescription(description.trim())
            setDuration(duration)
            setEating(eating)
            mutableState.value = BehaviourState.Loading
            val language = selectedLanguage()
            savedState["language"] = language
            Log.i(TAG, "Loading model for animal=$animalId")
            engine.load()
            currentCoroutineContext().ensureActive()
            val translation = translator.translateResult(description.trim(), language)
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            val result = engine.structureBehaviour(translation.text, duration, eating)
            currentCoroutineContext().ensureActive()
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val parsed = result.getOrThrow()
            val fields = if (translation.isFallback) parsed.copy(confidence = "low", usedFallback = true)
                .withContextSafety(duration, eating, translation.text) else parsed
            savedState["result"] = Json.encodeToString(fields)
            savedState["elapsedMs"] = elapsedMs
            savedState["usedFallback"] = fields.usedFallback
            mutableState.value = BehaviourState.Success(fields, elapsedMs, fields.usedFallback)
            Log.i(TAG, "Behaviour complete animal=$animalId elapsedMs=$elapsedMs usedFallback=${fields.usedFallback}")
        } catch (failure: CancellationException) {
            mutableState.value = BehaviourState.Idle
            throw failure
        } catch (failure: Throwable) {
            Log.e(TAG, "Behaviour analysis failed animal=$animalId", failure)
            mutableState.value = BehaviourState.Error(failure.message ?: "Could not analyse behaviour. Please retry.")
        }
    }

    suspend fun createVetCase(): kotlin.Result<Pair<Long, String>> = try {
        require(description.value.isNotBlank() && duration.value.isNotBlank()) { "Enter the observations first." }
        val transcript = "${description.value.trim()}\nDuration: ${duration.value}. " +
            "Eating and drinking normally: ${eating.value}."
        val language = savedState.get<String>("language") ?: selectedLanguage()
        val caseId = repository.saveDraft(animalId, transcript, language, draftKey)
        savedState["createdCaseId"] = caseId
        Log.i(TAG, "Vet case draft saved case=$caseId animal=$animalId")
        kotlin.Result.success(caseId to transcript)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        Log.e(TAG, "Vet case draft failed animal=$animalId", failure)
        kotlin.Result.failure(failure)
    }

    private fun restoreState(): BehaviourState = try {
        val raw = savedState.get<String>("result")
        if (raw == null) BehaviourState.Idle else {
            val fallback = savedState.get<Boolean>("usedFallback") ?: false
            val fields = Json.decodeFromString<BehaviourFields>(raw).copy(usedFallback = fallback)
            BehaviourState.Success(fields, savedState.get<Long>("elapsedMs") ?: 0L, fallback)
        }
    } catch (failure: Throwable) {
        Log.w(TAG, "Saved behaviour result unavailable", failure)
        BehaviourState.Idle
    }

    private companion object { const val TAG = "AhbBehaviour" }
}
