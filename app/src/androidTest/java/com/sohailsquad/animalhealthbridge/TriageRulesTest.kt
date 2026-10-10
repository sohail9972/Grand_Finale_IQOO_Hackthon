package com.sohailsquad.animalhealthbridge

import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sohailsquad.animalhealthbridge.ml.llm.StructuredCase
import com.sohailsquad.animalhealthbridge.triage.TriageEngine
import com.sohailsquad.animalhealthbridge.triage.TriageLevel
import com.sohailsquad.animalhealthbridge.triage.TriageRulesFile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TriageRulesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allSixRulesReturnTheirConfiguredReason() {
        val rules = context.assets.open("triage_rules.json").bufferedReader().use {
            Json.decodeFromString<TriageRulesFile>(it.readText()).rules
        }
        assertEquals(listOf("R1", "R2", "R3", "R4", "R5", "R6"), rules.map { it.id })
        val cases = listOf(
            StructuredCase(redFlags = listOf("cannot stand")) to "R1",
            StructuredCase(severityIndicators = listOf("bloated")) to "R2",
            StructuredCase(duration = "one week", confidence = "high") to "R3",
            StructuredCase(severityIndicators = listOf("limping"), confidence = "high") to "R4",
            StructuredCase(confidence = "low") to "R5",
            StructuredCase(duration = "today", confidence = "high") to "R6",
        )
        val engine = TriageEngine(context)
        cases.forEach { (case, expectedId) ->
            val result = engine.evaluate(case)
            val rule = rules.single { it.id == expectedId }
            assertEquals(expectedId, result.matchedRuleId)
            assertEquals(rule.action, result.level)
            assertEquals(rule.reason, result.reason)
            assertTrue(result.reason.isNotBlank())
            assertEquals(rule.reason, engine.reasonForRule(expectedId))
        }
    }

    @Test
    fun emergencyAndEarlierMatchesTakePriority() {
        val engine = TriageEngine(context)
        assertEquals("R1", engine.evaluate(StructuredCase(redFlags = listOf("bleeding"),
            severityIndicators = listOf("bloated"), duration = "one week")).matchedRuleId)
        val emergency = engine.evaluate(StructuredCase(severityIndicators = listOf("limping", "bloated"),
            duration = "one week", confidence = "low"))
        assertEquals("R2", emergency.matchedRuleId)
        assertEquals(TriageLevel.EMERGENCY_NOW, emergency.level)
        assertEquals("R3", engine.evaluate(StructuredCase(duration = "many days",
            severityIndicators = listOf("swelling"), confidence = "low")).matchedRuleId)
        assertEquals("R4", engine.evaluate(StructuredCase(severityIndicators = listOf("fever"),
            confidence = "low")).matchedRuleId)
    }

    @Test
    fun matchingIgnoresCaseAndEmptyNotStatedRedFlags() {
        val engine = TriageEngine(context)
        assertEquals("R2", engine.evaluate(StructuredCase(severityIndicators = listOf("DIFFICULTY BREATHING"))).matchedRuleId)
        assertEquals("R5", engine.evaluate(StructuredCase(confidence = " LOW ")).matchedRuleId)
        assertEquals("R6", engine.evaluate(StructuredCase(redFlags = listOf("", " NOT STATED "),
            confidence = "high")).matchedRuleId)
    }

    @Test
    fun unavailableRulesEscalateWithoutCrashing() {
        val unavailable = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw IllegalStateException("Test-only asset failure")
        }
        val result = TriageEngine(unavailable).evaluate(StructuredCase(confidence = "high"))
        assertEquals(TriageLevel.EMERGENCY_NOW, result.level)
        assertEquals("RULES_UNAVAILABLE", result.matchedRuleId)
        assertTrue(result.reason.isNotBlank())
    }
}
