package com.sohailsquad.animalhealthbridge.triage

import kotlinx.serialization.Serializable

@Serializable
enum class TriageLevel { EMERGENCY_NOW, SEE_VET_TODAY, MONITOR }

@Serializable
data class TriageRule(
    val id: String,
    val field: String,
    val operator: String = "default",
    val values: List<String> = emptyList(),
    val action: TriageLevel,
    val reason: String,
)
@Serializable
data class TriageRulesFile(val rules: List<TriageRule>)
data class TriageResult(val level: TriageLevel, val matchedRuleId: String, val reason: String)
fun resolveTriageLevel(value: String): TriageLevel = TriageLevel.entries.firstOrNull { it.name == value }
    ?: TriageLevel.SEE_VET_TODAY
