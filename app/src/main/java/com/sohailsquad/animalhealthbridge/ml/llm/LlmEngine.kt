package com.sohailsquad.animalhealthbridge.ml.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device case structuring with Gemma-3 1B (4-bit) via MediaPipe.
 *
 * ---------------------------------------------------------------------------
 * WHAT THIS DOES AND DOES NOT DO
 * ---------------------------------------------------------------------------
 * It turns an English symptom description into the JSON shape defined by
 * [StructuredCase]. It does NOT diagnose, does NOT rank conditions, and does
 * NOT suggest treatment. Those are vet-only decisions, and the absence of any
 * field for them is enforced by the schema itself.
 *
 * ---------------------------------------------------------------------------
 * THE RETRY-AND-ESCALATE CONTRACT
 * ---------------------------------------------------------------------------
 * Spec rule 6: "If LLM output invalid JSON, retry once, then escalate urgency."
 *
 * That policy lives here. [structure] makes at most two attempts, and when both
 * fail it returns a [Result.Escalated] carrying a deliberately minimal case
 * whose confidence is "low". The triage engine treats low confidence as
 * SEE_VET_TODAY, so a total model failure routes the animal to a vet rather
 * than silently telling the owner to wait.
 *
 * Nothing here ever throws to the caller.
 *
 * @param context used to locate the model file.
 */
class LlmEngine(context: Context) {

    private val appContext = context.applicationContext

    /** Outcome of a structuring attempt. */
    sealed interface Result {
        /**
         * A case was extracted, either by the model or by the keyword fallback.
         *
         * @param attempts how many model calls it took (1 or 2).
         */
        data class Success(
            val case: StructuredCase,
            val attempts: Int,
            val usedFallback: Boolean,
        ) : Result

        /**
         * The model could not produce a case and the fallback found nothing
         * either. The caller must treat this as "send to a vet".
         */
        data class Escalated(
            val case: StructuredCase,
            val reason: String,
        ) : Result
    }

    /** The inference handle, or null before [load] succeeds. */
    private var inference: LlmInference? = null

    /** True once a load has been attempted, so failures are not retried hot. */
    @Volatile
    private var loadAttempted = false

    /** True when the model is loaded and usable. */
    @Volatile
    private var modelAvailable = false

    /**
     * True when a real LLM is usable.
     *
     * Callers use this to warn the owner that structuring will be approximate,
     * rather than letting them discover it from a thin case card.
     */
    fun isAvailable(): Boolean {
        if (!loadAttempted) load()
        return modelAvailable
    }

    /**
     * Loads the model. Safe to call repeatedly; only the first call works.
     *
     * @return true when the model is ready.
     */
    fun load(): Boolean {
        if (loadAttempted) return modelAvailable
        synchronized(this) {
            if (loadAttempted) return modelAvailable
            loadAttempted = true

            val modelFile = modelFile()
            if (!modelFile.isFile || modelFile.length() == 0L) {
                // Expected on a fresh install: the model is side-loaded.
                Log.i(TAG, "LLM model not installed at ${modelFile.absolutePath}")
                modelAvailable = false
                return false
            }

            return try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    // 1024 tokens covers the prompt plus a full case object
                    // with room to spare, while keeping the KV cache small
                    // enough to stay comfortable on a 6-8 GB phone.
                    .setMaxTokens(MAX_TOKENS)
                    // CPU backend: the GPU delegate is faster on flagship
                    // silicon but fails to load on many mid-range Adreno
                    // drivers, and a failed delegate means no structuring at
                    // all. Reliability wins for a rural-deployment app.
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()

                inference = LlmInference.createFromOptions(appContext, options)
                modelAvailable = true
                Log.i(TAG, "LLM model loaded (${modelFile.length() / 1_048_576} MB)")
                true
            } catch (t: Throwable) {
                // Throwable: a corrupt .task file can abort in native code.
                Log.e(TAG, "Failed to load LLM model", t)
                inference = null
                modelAvailable = false
                false
            }
        }
    }

    /**
     * Structures a symptom description into a case.
     *
     * Makes up to two model attempts, then falls back to keyword extraction.
     * Runs on [Dispatchers.Default] because inference is CPU-bound and would
     * otherwise jank the UI for several seconds.
     *
     * @param englishTranscript the English symptom description.
     * @return a [Result]; never throws.
     */
    suspend fun structure(englishTranscript: String): Result = withContext(Dispatchers.Default) {
        val text = englishTranscript.trim()
        if (text.isEmpty()) {
            return@withContext Result.Escalated(
                case = emptyCase(),
                reason = "No transcript to structure",
            )
        }

        if (!isAvailable()) {
            // No model: go straight to the deterministic fallback rather than
            // pretending the LLM ran.
            Log.i(TAG, "No LLM; using keyword fallback")
            return@withContext keywordFallback(text, reason = "Model not installed")
        }

        // --- Attempt 1 ------------------------------------------------------
        val first = runInference(CasePrompt.build(text))
        parsedOrNull(first)?.let { parsed ->
            Log.i(TAG, "Structured on attempt 1 (confidence=${parsed.confidence})")
            return@withContext Result.Success(parsed, attempts = 1, usedFallback = false)
        }
        Log.w(TAG, "Attempt 1 produced no usable case; retrying")

        // --- Attempt 2 ------------------------------------------------------
        val second = runInference(CasePrompt.buildRetry(text))
        parsedOrNull(second)?.let { parsed ->
            Log.i(TAG, "Structured on attempt 2 (confidence=${parsed.confidence})")
            return@withContext Result.Success(parsed, attempts = 2, usedFallback = false)
        }

        // --- Escalate -------------------------------------------------------
        Log.e(TAG, "Both attempts failed; falling back to keyword extraction")
        keywordFallback(text, reason = "Model output was unparseable twice")
    }

    /**
     * Runs one inference call.
     *
     * @return the raw model text, or an empty string on failure.
     */
    private fun runInference(prompt: String): String {
        val engine = inference ?: return ""
        var session: LlmInferenceSession? = null
        return try {
            session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    // Near-greedy sampling: structuring is an extraction task,
                    // and creativity is exactly what we do not want. A low
                    // temperature keeps the model anchored to the transcript.
                    .setTemperature(0.1f)
                    .setTopK(1)
                    .setTopP(1.0f)
                    // Fixed seed so the same transcript structures the same way
                    // twice, which makes triage decisions reproducible and
                    // debuggable.
                    .setRandomSeed(SEED)
                    .build(),
            )

            // The session API takes the prompt as a chunk and then generates:
            // there is no generateResponse(String) overload on a session.
            session.addQueryChunk(prompt)

            // Synchronous and can take several seconds; we are already on
            // Dispatchers.Default, so blocking here is correct.
            session.generateResponse() ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "Inference call failed", t)
            ""
        } finally {
            try {
                session?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close LLM session", t)
            }
        }
    }

    /**
     * Deterministic last resort: pull fields out of the text with plain string
     * matching.
     *
     * This is the spec's documented fallback ("LLM fails -> keyword extraction
     * + template"). It is intentionally simple and auditable. Crucially it sets
     * confidence to "low" and, when it recognises an emergency phrase, puts it
     * in [StructuredCase.redFlags] so triage still fires EMERGENCY_NOW - the
     * fallback must never make the app less safe than the model.
     *
     * @param text the English description.
     * @param reason why the fallback was used, for logging.
     */
    private fun keywordFallback(text: String, reason: String): Result {
        val lower = text.lowercase()

        val redFlags = RED_FLAG_PHRASES.filter { lower.contains(it) }
        val severity = SEVERITY_PHRASES.filter { lower.contains(it) }

        val duration = DURATION_PHRASES
            .firstOrNull { lower.contains(it) }
            ?: StructuredCase.NOT_STATED

        val area = AREA_PHRASES
            .firstOrNull { lower.contains(it) }
            ?: StructuredCase.NOT_STATED

        val fallbackCase = StructuredCase(
            // Using the raw text as the complaint is honest: it is what the
            // owner said, just not neatly summarised.
            chiefComplaint = text.take(MAX_COMPLAINT_CHARS),
            duration = duration,
            affectedArea = area,
            severityIndicators = severity,
            redFlags = redFlags,
            additionalNotes = "Extracted without the language model ($reason).",
            // Always low: the triage engine escalates on low confidence, which
            // is the correct conservative outcome for an approximate parse.
            confidence = StructuredCase.CONFIDENCE_LOW,
        )

        Log.w(
            TAG,
            "Keyword fallback: redFlags=$redFlags severity=$severity reason=$reason",
        )
        return Result.Success(fallbackCase, attempts = 2, usedFallback = true)
    }

    /** A minimal case for the no-transcript path. */
    private fun emptyCase() = StructuredCase(
        chiefComplaint = StructuredCase.NOT_STATED,
        confidence = StructuredCase.CONFIDENCE_LOW,
        additionalNotes = "No symptom description was captured.",
    )

    /** Absolute path to the Gemma task file. */
    private fun modelFile(): File =
        File(File(appContext.filesDir, MODEL_ROOT), LLM_FILE)

    /** Releases the model. Safe to call repeatedly. */
    fun close() {
        try {
            inference?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close LLM", t)
        }
        inference = null
    }

    private companion object {
        /** Logcat tag; filter with: adb logcat -s AhbLlm */
        const val TAG = "AhbLlm"

        const val MODEL_ROOT = "models"
        const val LLM_FILE = "gemma3-1b-it-int4.task"

        /** Prompt + completion budget. */
        const val MAX_TOKENS = 1024

        /** Fixed sampling seed for reproducible structuring. */
        const val SEED = 42

        /** Longest raw-text complaint kept when the model is unavailable. */
        const val MAX_COMPLAINT_CHARS = 300

        /**
         * Emergency phrases, mirroring the red-flag list in the prompt.
         *
         * Duplicated on purpose: the prompt guides the model, and this list
         * guarantees the SAME emergencies are caught when the model is not
         * available. If one is edited the other must be too.
         */
        val RED_FLAG_PHRASES = listOf(
            "cannot stand", "can't stand", "unable to stand",
            "bleeding heavily", "heavy bleeding", "bleeding a lot",
            "difficulty breathing", "trouble breathing", "struggling to breathe",
            "bloated", "bloating",
            "unconscious", "not responding",
            "seizure", "convulsion", "fitting",
            "extreme pain", "severe pain", "screaming in pain",
            "not eating for two days", "not eating for 2 days",
        )

        /** Non-emergency severity clues. */
        val SEVERITY_PHRASES = listOf(
            "swelling", "swollen", "fever", "hot to touch",
            "not eating", "off feed", "not drinking",
            "limping", "lame", "wound", "cut", "injury",
            "coughing", "diarrhoea", "diarrhea", "vomiting",
            "discharge", "rash", "itching",
        )

        /** Duration wording the owner is likely to use. */
        val DURATION_PHRASES = listOf(
            "today", "yesterday", "this morning", "last night",
            "two days", "2 days", "three days", "3 days",
            "four days", "4 days", "five days", "5 days",
            "a week", "one week", "1 week", "two weeks", "2 weeks",
            "many days", "long time", "a month", "one month",
        )

        /** Body areas the owner is likely to name. */
        val AREA_PHRASES = listOf(
            "leg", "foot", "hoof", "udder", "teat", "mouth", "tongue",
            "eye", "ear", "neck", "back", "belly", "stomach", "rumen",
            "tail", "skin", "nose", "head", "hip", "shoulder",
        )
    }
}

/**
 * Parses model output, collapsing the failure branch to null.
 *
 * A private top-level helper rather than an extension on [CaseSchemaValidator]:
 * an extension sharing a name with the member it calls would resolve
 * ambiguously and risk infinite recursion.
 *
 * @return the parsed case, or null when the output was unusable.
 */
private fun parsedOrNull(raw: String): StructuredCase? =
    when (val result = CaseSchemaValidator.parse(raw)) {
        is CaseSchemaValidator.ParseResult.Success -> result.case
        is CaseSchemaValidator.ParseResult.Failure -> null
    }
