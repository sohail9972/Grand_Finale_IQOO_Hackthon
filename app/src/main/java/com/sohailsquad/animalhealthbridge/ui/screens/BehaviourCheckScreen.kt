package com.sohailsquad.animalhealthbridge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ml.asr.AndroidAsr
import com.sohailsquad.animalhealthbridge.ml.asr.AsrEngine
import com.sohailsquad.animalhealthbridge.ui.components.AhbCard
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.components.AhbStatusBadge
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun BehaviourCheckScreen(model: BehaviourViewModel, onAnalyse: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val androidAsr = remember(context) { AndroidAsr(context) }
    val asr = remember(context) { AsrEngine(context) }
    val sherpaStatus by asr.status.collectAsStateWithLifecycle()
    val language by context.app().preferences.language.collectAsStateWithLifecycle(initialValue = "hi")
    var usingSherpa by remember { mutableStateOf(false) }
    val description by model.description.collectAsStateWithLifecycle()
    val duration by model.duration.collectAsStateWithLifecycle()
    val eating by model.eating.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    var speechStatus by remember { mutableStateOf("Ready") }
    var recording by remember { mutableStateOf(false) }
    var speechJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = recording || state == BehaviourState.Loading

    fun listen() {
        if (busy) return
        recording = true
        error = null
        val previous = description.trim()
        fun updateTranscript(spoken: String) {
            if (spoken.isNotBlank()) model.setDescription(listOf(previous, spoken.trim())
                .filter { it.isNotBlank() }.joinToString(" "))
        }
        speechJob = scope.launch {
            speechStatus = "Listening..."
            try {
                val code = context.app().preferences.selectedLanguage()
                usingSherpa = asr.isAvailable(code)
                Log.i("AhbAsr", "Speak requested lang=$code; IndicConformer available=$usingSherpa")
                var result = if (usingSherpa) asr.listen(language = code, onPartial = { updateTranscript(it) })
                    else Result.failure<String>(IllegalStateException("IndicConformer model or tokens unavailable for $code."))
                currentCoroutineContext().ensureActive()
                if (result.isFailure) {
                    usingSherpa = false
                    speechStatus = "Trying system voice input. Please speak again."
                    Log.w("AhbAsr", "IndicConformer unavailable for $code; trying AndroidAsr", result.exceptionOrNull())
                    result = androidAsr.listen("$code-IN")
                }
                result.fold(onSuccess = {
                    updateTranscript(it)
                    speechStatus = "Finished. Review the description below."
                }, onFailure = {
                    speechStatus = "Voice unavailable, please type"
                    error = speechStatus
                    Log.w("AhbAsr", "Behaviour speech unavailable", it)
                    Toast.makeText(context, speechStatus, Toast.LENGTH_SHORT).show()
                })
            } catch (failure: CancellationException) {
                speechStatus = "Ready"
                throw failure
            } catch (failure: Throwable) {
                Log.w("AhbAsr", "Behaviour speech failed", failure)
                speechStatus = "Voice unavailable, please type"
                error = speechStatus
                Toast.makeText(context, speechStatus, Toast.LENGTH_SHORT).show()
            } finally { usingSherpa = false; recording = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else {
            error = "Microphone permission denied. Please type the observations."
            Log.i("AhbAsr", "Microphone permission denied")
            Toast.makeText(context, "Voice unavailable, please type", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(lifecycle, androidAsr, asr) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                asr.stopListening()
                speechJob?.cancel()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            asr.close()
            speechJob?.cancel()
        }
    }

    BehaviourContent("Check Behaviour", onBack) {
        AhbCard(title = "What is the pet doing?", subtitle = "Describe what you observe, without guessing a diagnosis.") {
            OutlinedTextField(value = description, onValueChange = model::setDescription,
                label = { Text("What is the pet doing?") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = MaterialTheme.shapes.large)
            if (language in listOf("hi", "kn", "ta", "te", "ml", "mr", "bn", "gu", "pa") ||
                (language == "en" && asr.isAvailable(language))) {
                AhbSecondaryButton(if (recording) "Stop speaking" else "Speak", onClick = {
                    if (recording) {
                        if (usingSherpa) {
                            if (sherpaStatus == "Listening") asr.stopListening() else speechJob?.cancel()
                        } else androidAsr.stopListening()
                    }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) listen()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, icon = if (recording) Icons.Default.StopCircle else Icons.Default.Mic,
                    enabled = state != BehaviourState.Loading, modifier = Modifier.fillMaxWidth())
                Text(if (usingSherpa) sherpaStatus else speechStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recording) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        AhbCard(title = "How long has this been happening?") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("hours", "days", "week+").forEach { option ->
                    FilterChip(selected = duration == option, onClick = { model.setDuration(option) },
                        label = { Text(option) }, enabled = !busy)
                }
            }
        }
        AhbCard(title = "Eating and drinking normally?") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Yes", "No", "Not sure").forEach { option ->
                    FilterChip(selected = eating == option, onClick = { model.setEating(option) },
                        label = { Text(option) }, enabled = !busy)
                }
            }
        }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AhbPrimaryButtonWide(
            text = "Analyse Behaviour",
            onClick = onAnalyse,
            enabled = description.isNotBlank() && duration.isNotBlank() && !busy
        )
        if (!(description.isNotBlank() && duration.isNotBlank() && !busy)) {
            Text(
                text = "Enter a description and select a duration to continue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@Composable
internal fun BehaviourContent(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            AhbStatusBadge("OFFLINE", icon = Icons.Default.CloudOff, modifier = Modifier.padding(end = 12.dp))
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(LocalAhbDimens.current.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap), content = content)
    }
}
