package com.sohailsquad.animalhealthbridge.triage

import android.content.Context
import android.util.Log
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import kotlinx.serialization.json.Json

class TriageEngine(context: Context) {
    private val rules = try {
        context.assets.open("triage_rules.json").bufferedReader().use {
            Json.decodeFromString<TriageRulesFile>(it.readText()).rules.also { loaded ->
                require(loaded.isNotEmpty() && loaded.map { rule -> rule.id }.distinct().size == loaded.size)
                require(loaded.last().field == "default" && loaded.last().action == TriageLevel.MONITOR)
                require(loaded.dropLast(1).none { rule -> rule.field == "default" })
                require(loaded.any { rule -> rule.field == "red_flags" && rule.operator == "is_not_empty" &&
                    rule.action == TriageLevel.EMERGENCY_NOW })
                require(loaded.any { rule -> rule.field == "confidence" && rule.values.contains("low") &&
                    rule.action != TriageLevel.MONITOR })
                loaded.forEach { rule ->
                    require(rule.id.isNotBlank() && rule.reason.isNotBlank())
                    require(rule.field in setOf("red_flags", "severity_indicators", "duration", "confidence", "default"))
                    require(when (rule.field) {
                        "red_flags" -> rule.operator == "is_not_empty"
                        "severity_indicators", "duration" -> rule.operator == "contains_any"
                        "confidence" -> rule.operator == "equals"
                        else -> rule.operator == "default"
                    })
                    if (rule.operator in setOf("contains_any", "equals")) {
                        require(rule.values.isNotEmpty() && rule.values.all { value -> value.isNotBlank() })
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        Log.e("AhbTriage", "Rules unavailable; conservative fallback", failure)
        emptyList()
    }

    fun evaluate(case: StructuredCase): TriageResult {
        var firstMatch: TriageRule? = null
        for (rule in rules) {
            if (matches(rule, case)) {
                if (rule.action == TriageLevel.EMERGENCY_NOW) {
                    return logged(TriageResult(rule.action, rule.id, rule.reason))
                }
                if (firstMatch == null) firstMatch = rule
            }
        }
        val result = firstMatch?.let { TriageResult(it.action, it.id, it.reason) }
            ?: TriageResult(TriageLevel.EMERGENCY_NOW, "RULES_UNAVAILABLE", UNAVAILABLE_REASON)
        return logged(result)
    }

    fun reasonForRule(ruleId: String): String =
        rules.firstOrNull { it.id == ruleId }?.reason
            ?: if (ruleId == "RULES_UNAVAILABLE") UNAVAILABLE_REASON
            else "The saved triage rule is unavailable. Ask a veterinarian to review this case."

    private fun matches(rule: TriageRule, case: StructuredCase): Boolean {
        val fields = when (rule.field) {
            "red_flags" -> case.redFlags
            "severity_indicators" -> case.severityIndicators
            "duration" -> listOf(case.duration)
            "confidence" -> listOf(case.confidence)
            else -> emptyList()
        }
        return when (rule.operator) {
            "is_not_empty" -> fields.any { it.isNotBlank() && !it.trim().equals(StructuredCase.NOT_STATED, ignoreCase = true) }
            "contains_any" -> fields.any { value -> rule.values.any { value.contains(it, ignoreCase = true) } }
            "equals" -> fields.any { value -> rule.values.any { value.trim().equals(it, ignoreCase = true) } }
            else -> rule.field == "default"
        }
    }

    private fun logged(result: TriageResult): TriageResult {
        Log.i("AhbTriage", "rule=${result.matchedRuleId} level=${result.level} reason=${result.reason}")
        return result
    }

    private companion object {
        const val UNAVAILABLE_REASON = "Unable to assess safely; seek urgent veterinary help."
    }
}
