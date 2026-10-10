package com.sohailsquad.animalhealthbridge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sohailsquad.animalhealthbridge.app
import com.sohailsquad.animalhealthbridge.ml.asr.AndroidAsr
import com.sohailsquad.animalhealthbridge.ml.asr.AsrEngine
import com.sohailsquad.animalhealthbridge.navigation.AhbDestination
import com.sohailsquad.animalhealthbridge.ui.components.*
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun VoiceIntakeScreen(animalId: Long, onTranscriptReady: (Long, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.app()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val androidAsr = remember { AndroidAsr(context) }
    val asr = remember { AsrEngine(context) }
    val sherpaStatus by asr.status.collectAsState()
    var usingSherpa by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    val language by application.preferences.language.collectAsState(initial = "hi")
    val animal by produceState<com.sohailsquad.animalhealthbridge.data.db.entity.AnimalProfile?>(null, animalId) {
        try { value = application.database.animalDao().getById(animalId) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) { Log.w("AhbRecord", "Intake header unavailable", failure) }
    }
    var transcript by rememberSaveable { mutableStateOf("") }
    val draftKey = rememberSaveable { UUID.randomUUID().toString() }
    var recording by remember { mutableStateOf(false) }
    var speechJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveLock = remember { Mutex() }
    suspend fun saveCurrentDraft(): Long = saveLock.withLock {
        application.caseRepository.saveDraft(animalId, transcript.trim(),
            application.preferences.selectedLanguage(), draftKey)
    }
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    fun listen() {
        if (recording || busy) return
        recording = true
        speechJob = scope.launch {
            error = null
            status = "Listening..."
            try {
                val code = application.preferences.selectedLanguage()
                usingSherpa = asr.isAvailable(code)
                Log.i("AhbAsr", "Speak requested lang=$code; IndicConformer available=$usingSherpa")
                var result = if (usingSherpa) asr.listen(language = code, onPartial = { transcript = it })
                    else Result.failure<String>(IllegalStateException("IndicConformer model or tokens unavailable for $code."))
                currentCoroutineContext().ensureActive()
                if (result.isFailure) {
                    usingSherpa = false
                    status = "Trying system voice input. Please speak again."
                    Log.w("AhbAsr", "IndicConformer unavailable for $code; trying AndroidAsr", result.exceptionOrNull())
                    result = androidAsr.listen("$code-IN")
                }
                result.fold(onSuccess = {
                    transcript = it
                    status = "Finished. Review the text, then press Continue."
                }, onFailure = {
                    status = "Voice unavailable, please type"
                    error = status
                    Log.w("AhbAsr", "Intake speech unavailable", it)
                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                })
            } catch (failure: CancellationException) {
                status = "Ready"
                throw failure
            } catch (failure: Throwable) {
                status = "Voice unavailable, please type"
                error = status
                Log.w("AhbAsr", "Intake speech failed", failure)
                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
            } finally { usingSherpa = false; recording = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) listen() else {
            error = "Microphone permission denied. Type the symptoms instead."
            Log.i("AhbAsr", "Microphone permission denied")
            Toast.makeText(context, "Voice unavailable, please type", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(lifecycle, androidAsr, asr) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { asr.stopListening(); speechJob?.cancel() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); asr.close(); speechJob?.cancel() }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { transcript }.debounce(600).distinctUntilChanged().collect { value ->
            if (value.isNotBlank()) try {
                saveCurrentDraft()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                error = "Draft could not be saved. Retry before leaving this screen."
                Log.w("AhbRecord", "Draft autosave failed", failure)
            }
        }
    }
    OfflineContent(AhbDestination.VoiceIntake, onBack, error, busy) {
        AhbStatusBadge("Input language: $language", icon = Icons.Default.Mic)
        animal?.let { AhbPatientHeader(it) }
        AhbCard(title = "Voice input", subtitle = "IndicConformer CTC · System speech fallback") {
            if (recording) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(if (usingSherpa) sherpaStatus else status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!asr.isAvailable(language) && !androidAsr.isAvailable()) Text("On-device voice input unavailable. Please type the symptoms below.")
        if (asr.isAvailable(language) || androidAsr.isAvailable()) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(216.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !recording && !busy, shape = CircleShape,
                modifier = Modifier.size(LocalAhbDimens.current.micButton).scale(if (recording) pulse else 1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mic, "Speak symptoms", Modifier.size(40.dp))
                    Text(if (recording) "Listening" else "Speak")
                }
            }
            }
        }
        }
        if (recording) Button(onClick = {
            if (usingSherpa) {
                if (sherpaStatus == "Listening") asr.stopListening() else speechJob?.cancel()
            } else androidAsr.stopListening()
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError)) {
            Icon(Icons.Default.StopCircle, null, Modifier.padding(end = 8.dp))
            Text("Stop speaking")
        }
        AhbCard(title = "Written symptoms",
            subtitle = "Describe observations freely in your own language") {
            InputField("Symptoms, in your own words", transcript, { transcript = it })
        }
        AhbSecondaryButton("Type Instead", onClick = { asr.stopListening(); speechJob?.cancel() },
            icon = Icons.Default.Keyboard, modifier = Modifier.fillMaxWidth())
        AhbPrimaryButtonWide("Continue", enabled = transcript.isNotBlank() && !recording && !busy,
            loading = busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val id = saveCurrentDraft()
                    onTranscriptReady(id, transcript.trim())
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Throwable) {
                    error = failure.message ?: "Could not save the draft. Retry."
                    Log.e("AhbRecord", "Continue failed", failure)
                } finally { busy = false }
            }
        })
    }
}
