package com.sohailsquad.animalhealthbridge.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.camera.EvidenceStore
import com.sohailsquad.animalhealthbridge.data.db.entity.VetQuery
import com.sohailsquad.animalhealthbridge.ml.asr.AndroidAsr
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import java.io.File

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun VetQueryScreen(caseId: Long, viewMode: String, onBack: () -> Unit,
    initialVetName: String = "", initialVetId: String = "") {
    val context = LocalContext.current
    val application = context.app()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val androidAsr = remember(context) { AndroidAsr(context) }
    val vetMode = viewMode.equals("vet", ignoreCase = true)
    val ownerMode = viewMode.equals("owner", ignoreCase = true)
    var busy by remember(caseId) { mutableStateOf(false) }
    var error by remember(caseId) { mutableStateOf<String?>(null) }
    var showQuestionDialog by rememberSaveable(caseId) { mutableStateOf(false) }
    var question by rememberSaveable(caseId) { mutableStateOf("") }
    var vetName by rememberSaveable(caseId) { mutableStateOf(initialVetName) }
    var vetId by rememberSaveable(caseId) { mutableStateOf(initialVetId) }
    var type by rememberSaveable(caseId) { mutableStateOf("TEXT") }
    var answers by rememberSaveable(caseId) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var media by rememberSaveable(caseId) { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    var captureQueryId by rememberSaveable(caseId) { mutableStateOf<Long?>(null) }
    var capturePath by rememberSaveable(caseId) { mutableStateOf<String?>(null) }
    var captureType by rememberSaveable(caseId) { mutableStateOf<String?>(null) }
    var requestedSpeechId by rememberSaveable(caseId) { mutableStateOf<Long?>(null) }
    var listeningQueryId by remember(caseId) { mutableStateOf<Long?>(null) }
    var speechJob by remember { mutableStateOf<Job?>(null) }
    val blocked = busy || captureQueryId != null || listeningQueryId != null
    val flow = remember(caseId) { application.database.vetQueryDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Questions could not be loaded."
        Log.w("AhbVet", "Query load failed case=$caseId", it)
        emit(emptyList())
    } }
    val queries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val evidenceFlow = remember(caseId) { application.database.evidenceDao().observeForCase(caseId).catch {
        if (it is CancellationException) throw it
        error = "Saved attachments could not be loaded. Answer in text instead."
        Log.w("AhbVet", "Evidence load failed case=$caseId", it)
        emit(emptyList())
    } }
    val savedEvidence by evidenceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pending = queries.filter { it.status == "PENDING" }

    LaunchedEffect(caseId, viewMode) {
        if (caseId <= 0 || (!vetMode && !ownerMode)) error = "Open a valid case in vet or owner mode."
        Log.i("AhbVet", "Query screen case=$caseId mode=$viewMode")
    }

    fun saveAnswer(queryId: Long, text: String, attachments: List<String>) {
        if (busy || captureQueryId != null || listeningQueryId != null || !ownerMode || caseId <= 0) return
        busy = true
        error = null
        scope.launch {
            try {
                val query = requireNotNull(application.database.vetQueryDao().getById(queryId)) {
                    "Question is no longer available."
                }
                require(query.caseId == caseId && query.status == "PENDING") { "Question is already answered." }
                require(text.isNotBlank() || attachments.isNotEmpty()) { "Type a response or attach evidence." }
                require(attachments.size <= 5) { "Use at most five attachments." }
                val mime = if (query.queryType == "VIDEO") "video/mp4" else "image/jpeg"
                val validated = attachments.distinct().map { EvidenceStore(context).validate(Uri.parse(it), mime) }
                application.database.withTransaction {
                    validated.forEach { application.caseRepository.addEvidence(caseId, it, mime) }
                    application.caseRepository.answer(caseId, queryId, text, validated)
                }
                answers = answers - queryId
                media = media - queryId
                Log.i("AhbVet", "Response saved case=$caseId query=$queryId type=${query.queryType}")
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                error = failure.message ?: "Response could not be saved. Retry."
                Log.e("AhbVet", "Response save failed case=$caseId query=$queryId", failure)
            } finally { busy = false }
        }
    }

    fun clearCapture(deleteFile: Boolean) {
        if (deleteFile) capturePath?.let { path ->
            runCatching { File(path).delete() }.onFailure { Log.w("AhbVet", "Capture cleanup failed", it) }
        }
        captureQueryId = null
        capturePath = null
        captureType = null
    }

    fun finishCapture(success: Boolean) {
        val queryId = captureQueryId
        val path = capturePath
        if (!success || queryId == null || path == null) {
            clearCapture(deleteFile = true)
            error = "Capture cancelled or unavailable. Try again or type a response."
            Log.i("AhbVet", "Capture not completed case=$caseId")
            return
        }
        clearCapture(deleteFile = false)
        val attachments = (media[queryId].orEmpty() + Uri.fromFile(File(path)).toString()).distinct()
        media = media + (queryId to attachments)
        saveAnswer(queryId, answers[queryId].orEmpty(), attachments)
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {
        finishCapture(it)
    }
    val videoContract = remember {
        object : ActivityResultContracts.CaptureVideo() {
            override fun createIntent(context: Context, input: Uri): Intent =
                super.createIntent(context, input).apply {
                    putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
                    putExtra(MediaStore.EXTRA_SIZE_LIMIT, 50L * 1024 * 1024)
                }
        }
    }
    val videoLauncher = rememberLauncherForActivityResult(videoContract) { finishCapture(it) }

    fun launchCapture() {
        try {
            val path = requireNotNull(capturePath) { "Choose a question before capture." }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
            Log.i("AhbVet", "Launching $captureType capture case=$caseId query=$captureQueryId")
            if (captureType == "VIDEO") videoLauncher.launch(uri) else photoLauncher.launch(uri)
        } catch (failure: Throwable) {
            clearCapture(deleteFile = true)
            error = "Camera unavailable. Please type a response instead."
            Log.w("AhbVet", "Camera launch failed case=$caseId", failure)
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture() else {
            clearCapture(deleteFile = true)
            error = "Camera permission denied. Please type a response instead."
            Log.i("AhbVet", "Camera permission denied case=$caseId")
        }
    }

    fun capture(query: VetQuery) {
        if (busy || captureQueryId != null || listeningQueryId != null || !ownerMode || caseId <= 0) return
        error = null
        try {
            val directory = File(context.filesDir, "evidence")
            require(directory.isDirectory || directory.mkdirs()) { "Evidence storage unavailable." }
            capturePath = File.createTempFile("vet_${caseId}_${query.id}_",
                if (query.queryType == "VIDEO") ".mp4" else ".jpg", directory).absolutePath
            captureQueryId = query.id
            captureType = query.queryType
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                launchCapture()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } catch (failure: Throwable) {
            clearCapture(deleteFile = true)
            error = "Capture unavailable. Please type a response instead."
            Log.w("AhbVet", "Capture setup failed case=$caseId", failure)
        }
    }

    fun listen(queryId: Long) {
        if (busy || captureQueryId != null || listeningQueryId != null || !ownerMode) return
        listeningQueryId = queryId
        error = null
        val previous = answers[queryId].orEmpty()
        speechJob = scope.launch {
            try {
                val language = application.preferences.selectedLanguage()
                Log.i("AhbVet", "Listening for response case=$caseId query=$queryId lang=$language")
                androidAsr.listen("$language-IN").fold(onSuccess = { text ->
                    answers = answers + (queryId to listOf(previous, text).filter { it.isNotBlank() }.joinToString(" "))
                }, onFailure = { failure ->
                    error = "Voice unavailable, please type"
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    Log.w("AhbVet", "Voice response unavailable query=$queryId", failure)
                })
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                error = "Voice unavailable, please type"
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                Log.w("AhbVet", "Voice response failed query=$queryId", failure)
            } finally { listeningQueryId = null }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val queryId = requestedSpeechId
        requestedSpeechId = null
        if (granted && queryId != null) listen(queryId) else {
            error = "Voice unavailable, please type"
            Log.i("AhbVet", "Microphone permission denied case=$caseId")
        }
    }
    DisposableEffect(lifecycle, androidAsr) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) speechJob?.cancel()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            speechJob?.cancel()
        }
    }

    OfflineContent(AhbDestination.VetQuery, onBack, error, busy) {
        AhbSectionHeading(if (vetMode) "Request field observations" else "Veterinarian questions",
            "Case #$caseId · Responses stay on this phone")
        AhbCard {
            Text("Local ${if (vetMode) "vet" else "owner"} view; no remote delivery or verified role.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vetMode) AhbPrimaryButtonWide("Add Question", enabled = caseId > 0 && !blocked,
            onClick = { showQuestionDialog = true })
        val visibleQueries = if (ownerMode) pending else queries
        if (visibleQueries.isEmpty()) AhbEmptyState(
            if (ownerMode && queries.isNotEmpty()) "All responses saved on this phone." else "No questions yet.",
            if (ownerMode) "Pending questions will appear here." else "Use Add Question to request an observation.")
        visibleQueries.forEach { query ->
            key(query.id) {
                AhbCard(title = "Question #${query.id}", subtitle = query.vetName) {
                    Text(if (ownerMode) query.questionTextNative.ifBlank { query.questionTextEnglish }
                        else query.questionTextEnglish, style = MaterialTheme.typography.titleMedium)
                    if (query.translationFallback) Text("Translation unavailable; original English shown.")
                    AhbStatusBadge("${query.queryType.replace('_', ' ')} · ${query.status}")
                    if (query.status == "ANSWERED") {
                        Text(query.answerText)
                        val uris = runCatching { Json.decodeFromString<List<String>>(query.answerMediaUris) }
                            .getOrDefault(emptyList())
                        uris.forEach { EvidencePreview(it, if (query.queryType == "VIDEO") "video/mp4" else "image/jpeg") }
                    } else if (ownerMode) {
                        val value = answers[query.id].orEmpty()
                        if (query.queryType == "YES_NO") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Yes", "No", "Not sure").forEach { response ->
                                    OutlinedButton(enabled = !blocked, onClick = { saveAnswer(query.id, response, emptyList()) }) {
                                        Text(response)
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(value = value, onValueChange = { answers = answers + (query.id to it) },
                                label = { Text(if (query.queryType == "TEXT") "Your response" else "Text response (optional fallback)") },
                                modifier = Modifier.fillMaxWidth(), enabled = !blocked, minLines = 2)
                            if (query.queryType == "TEXT") {
                                AhbSecondaryButton(if (listeningQueryId == query.id) "Stop speaking" else "Speak",
                                    enabled = listeningQueryId == query.id || !blocked, onClick = {
                                        if (listeningQueryId == query.id) androidAsr.stopListening()
                                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                            PackageManager.PERMISSION_GRANTED) listen(query.id)
                                        else {
                                            requestedSpeechId = query.id
                                            runCatching { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                                                .onFailure { error = "Voice unavailable, please type"; Log.w("AhbVet", "Microphone request failed", it) }
                                        }
                                    })
                                if (listeningQueryId == query.id) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                    Text("Listening...")
                                }
                            }
                            if (query.queryType in setOf("PHOTO", "VIDEO")) {
                                AhbSecondaryButton(if (query.queryType == "PHOTO") "Open Camera" else "Record Video",
                                    enabled = !blocked && media[query.id].orEmpty().size < 5, onClick = { capture(query) })
                                if (query.queryType == "VIDEO") Text("Record up to 30 seconds; maximum 50 MB.")
                                savedEvidence.filter {
                                    if (query.queryType == "PHOTO") it.mimeType == "image/jpeg" else it.mimeType == "video/mp4"
                                }.forEach { item ->
                                    val selected = item.uri in media[query.id].orEmpty()
                                    Row {
                                        Checkbox(checked = selected, enabled = !blocked && (selected || media[query.id].orEmpty().size < 5),
                                            onCheckedChange = { checked ->
                                                val current = media[query.id].orEmpty()
                                                media = media + (query.id to if (checked) (current + item.uri).distinct() else current - item.uri)
                                            })
                                        Text("Use saved attachment #${item.id}")
                                    }
                                }
                            }
                            media[query.id].orEmpty().forEach { uri ->
                                EvidencePreview(uri, if (query.queryType == "VIDEO") "video/mp4" else "image/jpeg")
                                TextButton(enabled = !blocked, onClick = {
                                    media = media + (query.id to (media[query.id].orEmpty() - uri))
                                }) { Text("Remove attachment") }
                            }
                            AhbPrimaryButtonWide("Save response", enabled = !blocked &&
                                (value.isNotBlank() || media[query.id].orEmpty().isNotEmpty()),
                                onClick = { saveAnswer(query.id, value, media[query.id].orEmpty()) })
                        }
                    }
                }
            }
        }
        if (ownerMode && queries.isNotEmpty() && pending.isEmpty()) {
            AhbPrimaryButtonWide("Send All Responses", enabled = !blocked, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        require(application.database.vetQueryDao().countPendingForCase(caseId) == 0) {
                            "A new question arrived. Answer it before continuing."
                        }
                        Log.i("AhbVet", "All responses saved locally case=$caseId")
                        onBack()
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Could not complete the responses. Retry."
                        Log.w("AhbVet", "Response completion failed case=$caseId", failure)
                    } finally { busy = false }
                }
            })
        }
    }

    if (showQuestionDialog && vetMode) AlertDialog(
        onDismissRequest = { if (!busy) showQuestionDialog = false },
        title = { Text("Add Question") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vetName, { vetName = it }, label = { Text("Vet name") }, enabled = !busy)
                OutlinedTextField(vetId, { vetId = it }, label = { Text("Registration number") }, enabled = !busy)
                OutlinedTextField(question, { question = it }, label = { Text("Question") }, enabled = !busy, minLines = 2)
                Text("Vet name, registration number and question are required.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PHOTO", "VIDEO", "YES_NO", "TEXT").forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option },
                            label = { Text(option) }, enabled = !busy)
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !blocked && question.isNotBlank() && vetName.isNotBlank() && vetId.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val case = application.caseRepository.get(caseId)
                        val translated = application.translator.translateResult(question.trim(), "en", case.sourceLang)
                        application.caseRepository.ask(VetQuery(caseId = caseId, vetId = vetId.trim(), vetName = vetName.trim(),
                            questionTextEnglish = question.trim(), questionTextNative = translated.text,
                            nativeLanguage = case.sourceLang, queryType = type, status = "PENDING",
                            askedAt = System.currentTimeMillis(), translationFallback = translated.isFallback))
                        Log.i("AhbVet", "Question sent locally case=$caseId type=$type")
                        question = ""
                        showQuestionDialog = false
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Throwable) {
                        error = failure.message ?: "Question could not be saved. Retry."
                        Log.e("AhbVet", "Question save failed case=$caseId", failure)
                    } finally { busy = false }
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { showQuestionDialog = false }) { Text("Cancel") } },
    )
}
