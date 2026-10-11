package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.data.db.entity.Case
import com.sohailsquad.animalhealthbridge.data.db.entity.Treatment
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

@Composable
fun VetApprovalScreen(caseId: Long, onRequestMoreInfo: (Long, String, String, String) -> Unit,
    onTreatmentApproved: (Long) -> Unit, onBack: () -> Unit) {
    val application = LocalContext.current.app()
    val scope = rememberCoroutineScope()
    var case by remember(caseId) { mutableStateOf<Case?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var approved by remember { mutableStateOf(false) }
    var fields by rememberSaveable(caseId) { mutableStateOf(List(12) { "" }) }
    val labels = listOf("Vet name", "Registration number", "Vet notes", "Medicine", "Dosage",
        "Frequency", "Duration", "Route", "Withdrawal period (days; enter 0 if none)",
        "Special instructions", "Follow-up date (YYYY-MM-DD; optional)", "Clinic / contact (optional)")
    val queriesFlow = remember(caseId) { application.database.vetQueryDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Questions could not be loaded; approval will recheck them."
        emit(emptyList())
    } }
    val queries by queriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Evidence could not be loaded; do not approve until reviewed."
        emit(emptyList())
    } }
    val evidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(caseId) {
        try {
            case = application.caseRepository.get(caseId)
            approved = application.treatmentRepository.getForCase(caseId) != null
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            error = failure.message ?: "Could not load this case."
            Log.e("AhbVet", "Review load failed", failure)
        } finally { busy = false }
    }
    OfflineContent(AhbDestination.VetApproval, onBack, error, busy) {
        AhbCard(title = "Veterinarian review", subtitle = "Saved on this phone · No remote delivery") {
            Text("Local veterinarian review. Credentials entered here are not independently verified.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        case?.let {
            AhbCard(title = "Case #${it.id} · Clinical observations") {
            AhbTriageBadge(com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel(it.triageLevel))
            ClinicalField("Case #${it.id}", it.chiefComplaint)
            ClinicalField("Duration", it.duration)
            ClinicalField("Affected area", it.affectedArea)
            ClinicalField("Severity", presentClinicalList(it.severityIndicatorsJson))
            ClinicalField("Red flags", presentClinicalList(it.redFlagsJson))
            ClinicalField("Confidence", it.confidence)
            ClinicalField("Observations", it.additionalNotes)
            Text(it.englishTranscript.ifBlank { it.originalTranscript })
            if (it.translationFallback) Text("Translation unavailable; original language preserved.")
            }
        }
        if (evidence.isNotEmpty()) AhbSectionHeading("Evidence to review", "${evidence.size} attachments")
        evidence.forEach { EvidencePreview(it.uri, it.mimeType) }
        val pending = queries.count { it.status == "PENDING" }
        AhbStatusBadge("Pending questions: $pending", tint = if (pending > 0)
            com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors.current.warning else MaterialTheme.colorScheme.primary)
        if (!approved) {
            AhbSecondaryButton("Request More Info", enabled = case != null && !busy,
                modifier = Modifier.fillMaxWidth(), onClick = {
                    Log.i("AhbVet", "Opening vet questions case=$caseId")
                    onRequestMoreInfo(caseId, "vet", fields[0].trim(), fields[1].trim())
                })
        }
        queries.filter { it.status == "ANSWERED" }.forEach {
            Text("Q: ${it.questionTextEnglish}\nA: ${it.answerText}\nMedia: ${it.answerMediaUris}")
        }
        if (approved) {
            Text("Treatment already approved and saved.")
            Button(onClick = { onTreatmentApproved(caseId) }) { Text("View prescription") }
        } else {
            listOf("Reviewer details" to listOf(0, 1, 11), "Clinical assessment" to listOf(2),
                "Treatment regimen" to listOf(3, 4, 5, 6, 7), "Withdrawal & follow-up" to listOf(8, 9, 10)).forEach { (title, indices) ->
                AhbCard {
                    AhbSectionHeading(title, icon = if (title == "Reviewer details") Icons.Default.VerifiedUser else Icons.Default.Medication)
                    indices.forEach { index ->
                        InputField(labels[index], fields[index], { value -> fields = fields.toMutableList().also { it[index] = value } })
                    }
                }
            }
            AhbPrimaryButtonWide("Approve Treatment", loading = busy, enabled = case != null && pending == 0 && !busy &&
                listOf(0, 1, 3, 4, 5, 6, 7, 8).all { fields[it].isNotBlank() }, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val withdrawal = requireNotNull(fields[8].trim().toIntOrNull()) { "Enter withdrawal days as a whole number." }
                        val treatment = Treatment(caseId = caseId, medicine = fields[3].trim(), dosage = fields[4].trim(),
                            frequency = fields[5].trim(), duration = fields[6].trim(), route = fields[7].trim(),
                            withdrawalPeriodDays = withdrawal, specialInstructions = fields[9].trim(),
                            followUpDate = parseDate(fields[10]), vetName = fields[0].trim(),
                            vetRegistrationNumber = fields[1].trim(),
                            vetNotes = listOf(fields[2], fields[11]).filter { it.isNotBlank() }.joinToString("\n"))
                        application.treatmentRepository.approve(treatment)
                        approved = true
                        onTreatmentApproved(caseId)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Approval failed. Check the fields and retry."
                        Log.e("AhbVet", "Approval failed", failure)
                    } finally { busy = false }
                }
            })
        }
    }
}
