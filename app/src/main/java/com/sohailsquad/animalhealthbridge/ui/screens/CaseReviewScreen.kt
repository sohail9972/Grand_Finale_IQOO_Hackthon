package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.camera.CaptureDialog
import com.sohailsquad.animalhealthbridge.camera.EvidenceStore
import com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.triage.TriageLevel
import com.sohailsquad.animalhealthbridge.triage.TriageResult
import com.sohailsquad.animalhealthbridge.triage.resolveTriageLevel
import com.sohailsquad.animalhealthbridge.ui.components.*
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun CaseReviewScreen(caseId: Long, onShareWithVet: (Long) -> Unit,
    onOpenQueries: (Long, String) -> Unit, onBack: () -> Unit, transcript: String = "") {
    val context = LocalContext.current
    val application = context.app()
    val scope = rememberCoroutineScope()
    val factory = remember(caseId) { object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            modelClass.cast(CaseReviewViewModel(context, caseId))!!
    } }
    val model: CaseReviewViewModel = viewModel(key = "review-$caseId", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val record by model.record.collectAsStateWithLifecycle()
    val modelAvailable by model.modelAvailable.collectAsStateWithLifecycle()
    var error by remember(caseId) { mutableStateOf<String?>(null) }
    var sharing by remember(caseId) { mutableStateOf(false) }
    var captureMode by remember(caseId) { mutableStateOf<String?>(null) }
    val busy = state == CaseReviewState.LoadingModel || state == CaseReviewState.Structuring
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Attachments could not be loaded."
        emit(emptyList())
    } }
    val evidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val animal by produceState<AnimalProfile?>(null, record?.animalId) {
        val animalId = record?.animalId ?: return@produceState
        try { value = application.database.animalDao().getById(animalId) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) { Log.w("AhbCaseReview", "Animal header unavailable", failure) }
    }
    LaunchedEffect(caseId, transcript) { model.processTranscript(transcript) }

    OfflineContent(AhbDestination.CaseReview, onBack, error, actions = {
        AhbPrimaryButtonWide("Share with Vet", enabled = record?.processingComplete == true && !busy && !sharing,
            loading = sharing, modifier = Modifier.padding(16.dp), onClick = {
                scope.launch {
                    sharing = true
                    error = null
                    try {
                        application.caseRepository.requestReview(caseId)
                        onShareWithVet(caseId)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Could not request review. Retry."
                        Log.e("AhbCaseReview", "Review request failed", failure)
                    } finally { sharing = false }
                }
            })
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Case Review", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AhbStatusBadge(when (state) {
                CaseReviewState.Idle -> "Idle"
                CaseReviewState.LoadingModel, CaseReviewState.Structuring -> "Loading"
                is CaseReviewState.Success -> "Ready"
                is CaseReviewState.Error -> "Needs review"
            })
        }
        animal?.let { AhbPatientHeader(it) }
        AhbCard(title = "Transcript", subtitle = record?.let { "Source language: ${it.sourceLang}" }) {
            Text(record?.originalTranscript ?: transcript.ifBlank { "No transcript available." })
        }
        val triageResult = (state as? CaseReviewState.Success)?.triageResult
            ?: record?.takeIf { it.processingComplete }?.let {
                TriageResult(resolveTriageLevel(it.triageLevel), it.triageRuleId,
                    application.triageEngine.reasonForRule(it.triageRuleId))
            }
        triageResult?.let { ReviewTriageBadge(it) }
        AhbCard(title = "AI Case Analysis", subtitle = "Extraction, not a diagnosis") {
            when (val analysis = state) {
                CaseReviewState.Idle -> Text("Waiting for transcript.")
                CaseReviewState.LoadingModel -> AnalysisProgress("Loading model...")
                CaseReviewState.Structuring -> AnalysisProgress("Analysing with Qwen3-1.7B...")
                is CaseReviewState.Success -> {
                    if (analysis.usedFallback) AhbStatusBadge("Fallback used", tint = LocalAhbColors.current.warning)
                    val parsed = analysis.parsed
                    AnalysisField("Main complaint", parsed.chiefComplaint, Icons.Default.MedicalServices)
                    AnalysisField("Duration", parsed.duration, Icons.Default.Schedule)
                    AnalysisField("Affected area", parsed.affectedArea, Icons.Default.Pets)
                    AnalysisField("Severity", parsed.severityIndicators.joinToString(" · ").ifBlank { "None recorded" },
                        Icons.Default.PriorityHigh)
                    AnalysisField("Red flags", parsed.redFlags.joinToString(" · ").ifBlank { "None recorded" },
                        Icons.Default.Warning)
                    AnalysisField("Confidence", parsed.confidence, Icons.AutoMirrored.Filled.FactCheck)
                    AnalysisField("Notes", parsed.additionalNotes, Icons.Default.Description)
                    Text(analysis.elapsedMs?.let { "Inference: ${it}ms" }
                        ?: "Saved analysis; inference timing was not recorded.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is CaseReviewState.Error -> {
                    AhbCard(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) {
                        Text(analysis.msg, color = MaterialTheme.colorScheme.error)
                    }
                    AhbSecondaryButton("Retry analysis", onClick = { scope.launch { model.processTranscript(transcript) } },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            if (state != CaseReviewState.Idle && state != CaseReviewState.LoadingModel) {
                AhbStatusBadge(if (modelAvailable) "On-device model loaded" else "Model not loaded",
                    tint = if (modelAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        record?.let { case ->
            AhbCard(title = "Local case #${case.id}", subtitle = case.status.replace('_', ' ')) {
                if (case.translationFallback) Text("Translation unavailable. Original text preserved; contact a veterinarian.")
            }
            AhbCard(title = "Field evidence", subtitle = "${evidence.size} of 5 captures attached") {
                if (evidence.isEmpty()) Text("No photos or videos attached yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                evidence.forEach { EvidencePreview(it.uri, it.mimeType) }
                if (case.status != "APPROVED" && evidence.size < 5) {
                    AhbSecondaryButton("Attach photo", onClick = { captureMode = "PHOTO" },
                        icon = Icons.Default.PhotoCamera, modifier = Modifier.fillMaxWidth())
                    AhbSecondaryButton("Attach video", onClick = { captureMode = "VIDEO" },
                        icon = Icons.Default.Videocam, modifier = Modifier.fillMaxWidth())
                }
            }
            AhbSecondaryButton("Answer vet questions", onClick = { onOpenQueries(caseId, "OWNER") },
                modifier = Modifier.fillMaxWidth())
            Text("Sharing opens local veterinarian review; nothing is sent over the network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    captureMode?.let { mode ->
        CaptureDialog(mode == "VIDEO", onCaptured = { uri, mime ->
            val validated = EvidenceStore(context).validate(uri, mime)
            application.caseRepository.addEvidence(caseId, validated, mime)
        }, onDismiss = { captureMode = null })
    }
}

@Composable
private fun ReviewTriageBadge(result: TriageResult) {
    val colors = LocalAhbColors.current
    val darkText = Color(0xFF1C1B1F)
    val (background, foreground, icon) = when (result.level) {
        TriageLevel.EMERGENCY_NOW -> Triple(Color(0xFFB3261E), Color.White, Icons.Default.Warning)
        TriageLevel.SEE_VET_TODAY -> Triple(colors.warning, darkText, Icons.Default.Schedule)
        TriageLevel.MONITOR -> Triple(colors.success, darkText, Icons.Default.CheckCircle)
    }
    val label = when (result.level) {
        TriageLevel.EMERGENCY_NOW -> "EMERGENCY — See vet now"
        TriageLevel.SEE_VET_TODAY -> "See vet today"
        TriageLevel.MONITOR -> "Monitor"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = background, contentColor = foreground) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
        }
        Text(result.reason, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnalysisProgress(message: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(message, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AnalysisField(label: String, value: String, icon: ImageVector) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

internal fun presentClinicalList(value: String): String =
    runCatching { Json.decodeFromString<List<String>>(value).joinToString(" · ").ifBlank { "None recorded" } }
        .getOrDefault(value)
