package com.sohailsquad.animalhealthbridge.ui.screens

import android.util.Log
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.camera.*
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun VetQueryScreen(caseId: Long, viewMode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    var vetName by rememberSaveable { mutableStateOf("") }
    var vetId by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("TEXT") }
    var answers by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var media by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    var captureQuery by remember { mutableStateOf<VetQuery?>(null) }
    val flow = remember(caseId) { application.database.vetQueryDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Questions could not be loaded."
        Log.w("AhbVet", "Query load failed", it)
        emit(emptyList())
    } }
    val queries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Saved attachments could not be loaded. Answer in text instead."
        emit(emptyList())
    } }
    val savedEvidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    OfflineContent(AhbDestination.VetQuery, onBack, error, busy) {
        AhbSectionHeading(if (viewMode == "VET") "Request field observations" else "Veterinarian questions",
            "Case #$caseId · Responses stay on this phone")
        AhbCard {
            Text("Local ${if (viewMode == "VET") "vet" else "owner"} view; no remote delivery or verified role.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (viewMode == "VET") {
            AhbCard(title = "New question") {
            InputField("Vet name", vetName, { vetName = it })
            InputField("Registration number", vetId, { vetId = it })
            InputField("Question in English", question, { question = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PHOTO", "VIDEO", "YES_NO", "TEXT").forEach { option ->
                    FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) },
                        modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.large)
                }
            }
            AhbPrimaryButtonWide("Save question locally", loading = busy,
                enabled = !busy && question.isNotBlank() && vetName.isNotBlank() && vetId.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val case = application.caseRepository.get(caseId)
                        val translated = application.translator.translateResult(question.trim(), "en", case.sourceLang)
                        application.caseRepository.ask(VetQuery(caseId = caseId, vetId = vetId.trim(), vetName = vetName.trim(),
                            questionTextEnglish = question.trim(), questionTextNative = translated.text,
                            nativeLanguage = case.sourceLang, queryType = type, translationFallback = translated.isFallback))
                        question = ""
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Question could not be saved. Retry."
                        Log.e("AhbVet", "Question save failed", failure)
                    } finally { busy = false }
                }
            })
            }
        }
        if (queries.isEmpty()) AhbEmptyState("No questions yet.", "Saved questions and field responses will appear here.")
        queries.forEach { query ->
            AhbCard(title = "Question #${query.id}", subtitle = query.vetName) {
            Text(if (viewMode == "OWNER") query.questionTextNative else query.questionTextEnglish,
                style = MaterialTheme.typography.titleMedium)
            if (query.translationFallback) Text("Translation unavailable; original English shown.")
            AhbStatusBadge("${query.queryType.replace('_', ' ')} · ${query.status}")
            if (query.status == "ANSWERED") {
                Text(query.answerText)
                val uris = runCatching { Json.decodeFromString<List<String>>(query.answerMediaUris) }.getOrDefault(emptyList())
                uris.forEach { EvidencePreview(it, if (it.endsWith(".mp4")) "video/mp4" else "image/jpeg") }
            } else if (viewMode != "VET") {
                val value = answers[query.id].orEmpty()
                InputField("Answer (text is always available)", value, { answers = answers + (query.id to it) })
                if (query.queryType == "YES_NO") listOf("Yes", "No", "Not sure").forEach { response ->
                    TextButton(onClick = { answers = answers + (query.id to response) }) { Text(response) }
                }
                if (query.queryType in setOf("PHOTO", "VIDEO")) {
                    TextButton(enabled = media[query.id].orEmpty().size < 5 && !busy,
                        onClick = { captureQuery = query }) { Text("Capture ${query.queryType.lowercase()}") }
                    savedEvidence.filter { item ->
                        if (query.queryType == "PHOTO") item.mimeType.startsWith("image/") else item.mimeType.startsWith("video/")
                    }.forEach { item ->
                        val selected = item.uri in media[query.id].orEmpty()
                        Row {
                            Checkbox(checked = selected, enabled = selected || media[query.id].orEmpty().size < 5,
                                onCheckedChange = { checked ->
                                    val current = media[query.id].orEmpty()
                                    media = media + (query.id to if (checked) (current + item.uri).distinct() else current - item.uri)
                                })
                            Text("Use saved attachment #${item.id}")
                        }
                    }
                }
                media[query.id].orEmpty().forEach { EvidencePreview(it, if (it.endsWith(".mp4")) "video/mp4" else "image/jpeg") }
            }
            }
        }
        if (viewMode != "VET") {
            val pending = queries.filter { it.status == "PENDING" }
            AhbPrimaryButtonWide("Send All Responses (save locally)", loading = busy,
                enabled = !busy && pending.isNotEmpty() && pending.all {
                answers[it.id].orEmpty().isNotBlank() || media[it.id].orEmpty().isNotEmpty()
            }, onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        application.database.withTransaction {
                            pending.forEach { application.caseRepository.answer(caseId, it.id,
                                answers[it.id].orEmpty(), media[it.id].orEmpty()) }
                        }
                        answers = emptyMap()
                        media = emptyMap()
                        Log.i("AhbVet", "All responses saved locally case=$caseId")
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Responses could not be saved. Retry."
                        Log.e("AhbVet", "Response save failed", failure)
                    } finally { busy = false }
                }
            })
        }
    }
    captureQuery?.let { query ->
        CaptureDialog(query.queryType == "VIDEO", onCaptured = { uri, mime ->
            val validated = EvidenceStore(context).validate(uri, mime)
            application.caseRepository.addEvidence(caseId, validated, mime)
            media = media + (query.id to (media[query.id].orEmpty() + validated))
        }, onDismiss = { captureQuery = null })
    }
}
