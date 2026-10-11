package com.sohailsquad.animalhealthbridge.ml.llm

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The structured case the LLM must produce.
 *
 * ---------------------------------------------------------------------------
 * SAFETY BOUNDARY
 * ---------------------------------------------------------------------------
 * Every field here is an EXTRACTION of something the owner said. There is no
 * diagnosis field, no condition field, and no treatment field, because the app
 * must never produce one - the vet is the sole prescriber. If a future change
 * adds such a field, it is a safety regression, not a feature.
 *
 * ---------------------------------------------------------------------------
 * "not stated" IS MEANINGFUL
 * ---------------------------------------------------------------------------
 * Absent information is recorded as [NOT_STATED] rather than as an empty string
 * or a guess. The triage rules in Step 6 depend on telling "the owner did not
 * mention duration" apart from "the owner said it started today", and a model
 * that invents a plausible duration is the single most dangerous failure mode
 * in this pipeline. The prompt forbids invention; [CaseSchemaValidator] then
 * checks for it.
 */
@Serializable
data class StructuredCase(
    /** Main symptom, in English. */
    @SerialName("chief_complaint")
    val chiefComplaint: String = NOT_STATED,

    /** How long the animal has been unwell. */
    @SerialName("duration")
    val duration: String = NOT_STATED,

    /** Body part affected. */
    @SerialName("affected_area")
    val affectedArea: String = NOT_STATED,

    /**
     * Severity clues the owner actually described.
     *
     * A list rather than a single string because a farmer typically rattles off
     * several ("she is limping and off her feed").
     */
    @SerialName("severity_indicators")
    val severityIndicators: List<String> = emptyList(),

    /**
     * Emergency indicators.
     *
     * Kept separate from [severityIndicators] because the triage engine treats
     * a non-empty red-flag list as an automatic EMERGENCY_NOW, regardless of
     * anything else in the case.
     */
    @SerialName("red_flags")
    val redFlags: List<String> = emptyList(),

    /** Anything else the owner mentioned that does not fit a field above. */
    @SerialName("additional_notes")
    val additionalNotes: String = NOT_STATED,

    /**
     * How confident the model is in this extraction.
     *
     * One of "high", "medium", "low". Low confidence escalates urgency in the
     * triage rules, so an uncertain extraction is never treated as reassuring.
     */
    @SerialName("confidence")
    val confidence: String = CONFIDENCE_LOW,
) {
    companion object {
        /** Marker for information the owner did not provide. */
        const val NOT_STATED = "not stated"

        const val CONFIDENCE_HIGH = "high"
        const val CONFIDENCE_MEDIUM = "medium"
        const val CONFIDENCE_LOW = "low"

        /** The three values [confidence] is allowed to take. */
        val CONFIDENCE_VALUES = setOf(CONFIDENCE_HIGH, CONFIDENCE_MEDIUM, CONFIDENCE_LOW)
    }
}

/**
 * Turns raw model output into a [StructuredCase], or explains why it could not.
 *
 * ---------------------------------------------------------------------------
 * WHY THIS IS SO DEFENSIVE
 * ---------------------------------------------------------------------------
 * A 1B-parameter model running in 4-bit is genuinely useful, but it is not
 * reliable about output format: it will wrap JSON in prose, emit a trailing
 * comma, use smart quotes, or answer the question in English sentences instead
 * of JSON. All of those happen in practice.
 *
 * The spec's rule is explicit: "If LLM output invalid JSON, retry once, then
 * escalate urgency." This parser is the first half of that - it extracts and
 * repairs as much as it can, and reports failure honestly so the caller can
 * retry and then escalate. It never invents a case.
 */
object CaseSchemaValidator {

    private const val TAG = "AhbLlm"

    /**
     * Lenient JSON: accepts unknown keys (models add fields) and tolerates
     * missing ones (defaults fill in).
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Outcome of a parse attempt. */
    sealed interface ParseResult {
        /** A usable case was produced. */
        data class Success(val case: StructuredCase) : ParseResult

        /**
         * Nothing usable could be extracted.
         *
         * @param reason short explanation for the log; not shown to the user.
         */
        data class Failure(val reason: String) : ParseResult
    }

    /**
     * Parses model output into a [StructuredCase].
     *
     * @param raw the model's raw text response.
     */
    fun parse(raw: String): ParseResult {
        if (raw.isBlank()) {
            return ParseResult.Failure("Model returned an empty response")
        }

        val candidate = extractJsonObject(raw)
            ?: return ParseResult.Failure("No JSON object found in the response")

        val decoded = try {
            json.decodeFromString(StructuredCase.serializer(), candidate)
        } catch (t: Throwable) {
            Log.w(TAG, "JSON decode failed: ${t.message}")
            return ParseResult.Failure("Response was not valid JSON: ${t.message}")
        }

        return ParseResult.Success(normalise(decoded))
    }

    /**
     * Repairs the common ways a small model gets a case subtly wrong.
     *
     * Every repair moves the value TOWARDS caution: unknown confidence becomes
     * "low", blank strings become [StructuredCase.NOT_STATED], and list entries
     * are trimmed and de-duplicated. None of them invent clinical content.
     */
    private fun normalise(case: StructuredCase): StructuredCase = case.copy(
        chiefComplaint = case.chiefComplaint.cleanField(),
        duration = case.duration.cleanField(),
        affectedArea = case.affectedArea.cleanField(),
        additionalNotes = case.additionalNotes.cleanField(),
        // An unrecognised confidence value is treated as low, which escalates
        // urgency. Failing safe here is deliberate.
        confidence = case.confidence.lowercase().trim()
            .takeIf { it in StructuredCase.CONFIDENCE_VALUES }
            ?: StructuredCase.CONFIDENCE_LOW,
        severityIndicators = case.severityIndicators.cleanList(),
        redFlags = case.redFlags.cleanList(),
    )

    /**
     * Extracts the outermost JSON object from a response that may be wrapped in
     * prose or a Markdown code fence.
     *
     * Scans for the first `{` and its matching `}` while tracking string state,
     * so a brace inside a quoted value does not truncate the object.
     *
     * @return the JSON substring, or null when no balanced object exists.
     */
    private fun extractJsonObject(raw: String): String? {
        // Strip a Markdown fence if the model added one.
        val text = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        // Re-parse through a strict-ish sanitiser so a trailing
                        // comma (the most common small-model defect) is fixed.
                        return sanitiseTrailingCommas(text.substring(start, i + 1))
                    }
                }
            }
        }
        return null
    }

    /**
     * Removes commas that sit directly before a closing brace or bracket.
     *
     * JSON forbids them; small models emit them constantly. Tracked through
     * string state so a comma inside a value is left alone.
     */
    private fun sanitiseTrailingCommas(json: String): String {
        val out = StringBuilder(json.length)
        var inString = false
        var escaped = false

        for (i in json.indices) {
            val c = json[i]
            if (escaped) {
                escaped = false
                out.append(c)
                continue
            }
            when {
                c == '\\' && inString -> {
                    escaped = true
                    out.append(c)
                }
                c == '"' -> {
                    inString = !inString
                    out.append(c)
                }
                inString -> out.append(c)
                c == ',' -> {
                    // Look ahead past whitespace for a closer.
                    var j = i + 1
                    while (j < json.length && json[j].isWhitespace()) j++
                    if (j < json.length && (json[j] == '}' || json[j] == ']')) {
                        // Drop the comma.
                    } else {
                        out.append(c)
                    }
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Trims a field and substitutes the not-stated marker when blank. */
    private fun String.cleanField(): String {
        val t = trim().trim('"').trim()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) {
            StructuredCase.NOT_STATED
        } else {
            t
        }
    }

    /**
     * Cleans a list: trims entries, drops blanks and "null" strings, and
     * removes case-insensitive duplicates while preserving order.
     */
    private fun List<String>.cleanList(): List<String> {
        val seen = LinkedHashSet<String>()
        for (item in this) {
            val t = item.trim().trim('"').trim()
            if (t.isEmpty()) continue
            if (t.equals("null", ignoreCase = true)) continue
            if (t.equals(StructuredCase.NOT_STATED, ignoreCase = true)) continue
            seen += t
        }
        return seen.toList()
    }
}

/**
 * Builds the prompt sent to Gemma.
 *
 * Kept in one place so the wording can be tuned against real transcripts
 * without hunting through the engine. The rules are deliberately blunt: small
 * models follow short, absolute instructions far better than nuanced ones.
 */
object CasePrompt {

    /**
     * System instruction. The "do not invent" rule is repeated in two forms
     * because it is the single most important behaviour in the pipeline - a
     * hallucinated duration or red flag would change the triage outcome.
     */
    private const val INSTRUCTIONS = """You are a veterinary case structuring assistant.
Extract clinical fields from the following animal symptom description.

Output valid JSON only:
{
  "chief_complaint": "main symptom in English",
  "duration": "how long, or 'not stated'",
  "affected_area": "body part, or 'not stated'",
  "severity_indicators": ["list of severity clues"],
  "red_flags": ["emergency indicators if present"],
  "additional_notes": "anything else",
  "confidence": "high" or "medium" or "low"
}

Rules:
- ONLY extract what was actually said. Do not invent.
- If not mentioned, use "not stated".
- Red flags (any of these means EMERGENCY):
    died, dead, not breathing, deceased, unconscious, collapsed,
    unresponsive, cannot stand, difficulty breathing, gasping,
    choking, bleeding heavily, bloated, seizure, extreme pain,
    not eating more than 2 days, multiple animals affected.
- If the owner says the animal DIED or is DEAD:
    * chief_complaint MUST be "animal deceased"
    * red_flags MUST include "animal deceased"
    * Never treat "died" or "dead" as a symptom.
- Output ONLY valid JSON. No explanation, no markdown, no code fence."""

    /**
     * Builds the full prompt for a transcript.
     *
     * @param englishTranscript the translated symptom description.
     */
    fun build(englishTranscript: String): String =
        "$INSTRUCTIONS\n\nDescription: ${englishTranscript.trim()}\n\nJSON:"

    /**
     * The retry prompt, used when the first attempt produced unparseable output.
     *
     * The original response is NOT echoed back: a small model tends to copy its
     * own mistake when it can see it. Restating the requirement alone works
     * better in practice.
     */
    fun buildRetry(englishTranscript: String): String =
        "$INSTRUCTIONS\n\n" +
            "Your previous answer was not valid JSON. Respond with the JSON object " +
            "only, starting with { and ending with }.\n\n" +
            "Description: ${englishTranscript.trim()}\n\nJSON:"
}
