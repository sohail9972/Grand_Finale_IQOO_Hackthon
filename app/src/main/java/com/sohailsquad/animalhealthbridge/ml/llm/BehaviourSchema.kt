package com.sohailsquad.animalhealthbridge.ml.llm

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale

@Serializable
data class BehaviourFields(
    val behaviour_summary: String = "not stated",
    val possible_causes: List<String> = emptyList(),
    val urgency: String = "low",
    val advice: String = "not stated",
    val confidence: String = "low",
    @Transient val usedFallback: Boolean = false,
)

const val BEHAVIOUR_SYSTEM_PROMPT = """
You are an animal behaviour analyst assistant. Based on the owner's
description and context, list possible reasons for the observed behaviour.

Output valid JSON only:
{
  "behaviour_summary": "one sentence describing what was observed",
  "possible_causes": ["3-5 plausible causes in plain language"],
  "urgency": "low" | "medium" | "high",
  "advice": "one sentence of practical advice",
  "confidence": "high" | "medium" | "low"
}

Rules:
- Do NOT diagnose. Only suggest possible causes.
- Do NOT prescribe. Only suggest consulting a vet if urgent.
- If duration is a week or more, urgency is at least "medium".
- If eating/drinking is abnormal, urgency is "high".
- If the owner says the animal DIED, is DEAD, or is NOT BREATHING,
  urgency MUST be "high" and advice MUST be "Contact a veterinarian
  immediately."
- Output ONLY valid JSON.
"""

private val behaviourJson = Json { ignoreUnknownKeys = true }
private val behaviourLevels = listOf("low", "medium", "high")
private val behaviourKeys = setOf("behaviour_summary", "possible_causes", "urgency", "advice", "confidence")

fun parseBehaviourJson(raw: String): BehaviourFields? = try {
    val candidate = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val objectValue = behaviourJson.parseToJsonElement(candidate) as? JsonObject
    if (objectValue == null || !objectValue.keys.containsAll(behaviourKeys)) {
        Log.w("AhbBehaviour", "Response is missing behaviour fields")
        null
    } else {
        val decoded = behaviourJson.decodeFromJsonElement<BehaviourFields>(objectValue)
        val cleaned = decoded.copy(
            behaviour_summary = decoded.behaviour_summary.trim(),
            possible_causes = decoded.possible_causes.map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) },
            urgency = decoded.urgency.trim().lowercase(Locale.ROOT),
            advice = decoded.advice.trim(),
            confidence = decoded.confidence.trim().lowercase(Locale.ROOT),
        )
        if (cleaned.behaviour_summary.isBlank() || cleaned.behaviour_summary == "not stated" ||
            cleaned.advice.isBlank() || cleaned.advice == "not stated" ||
            cleaned.possible_causes.size !in 3..5 || cleaned.urgency !in behaviourLevels ||
            cleaned.confidence !in behaviourLevels) {
            Log.w("AhbBehaviour", "Response has invalid behaviour values")
            null
        } else cleaned
    }
} catch (failure: Exception) {
    Log.w("AhbBehaviour", "Response is not valid behaviour JSON", failure)
    null
}

internal fun BehaviourFields.withContextSafety(
    duration: String,
    eatingNormally: String,
    description: String,
    emergency: Boolean = false,
): BehaviourFields {
    val longDuration = Regex("\\b(weeks?|months?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(duration)
    val abnormalEating = eatingNormally.trim().equals("No", ignoreCase = true) ||
        listOf("not eating", "not drinking", "stopped eating", "stopped drinking", "refusing food", "refusing water")
            .any { description.lowercase(Locale.ROOT).contains(it) }
    val minimum = when {
        emergency || abnormalEating -> "high"
        longDuration || usedFallback -> "medium"
        else -> "low"
    }
    val resolved = behaviourLevels[maxOf(behaviourLevels.indexOf(urgency).coerceAtLeast(0),
        behaviourLevels.indexOf(minimum))]
    return copy(urgency = resolved, advice = when {
        resolved == "high" -> "Consult a veterinarian urgently about these observations."
        resolved == "medium" && urgency == "low" -> "Consult a veterinarian if this persists or worsens."
        else -> advice
    })
}
