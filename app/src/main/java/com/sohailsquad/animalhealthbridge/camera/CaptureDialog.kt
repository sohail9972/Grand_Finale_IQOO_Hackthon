package com.sohailsquad.animalhealthbridge.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import com.sohailsquad.animalhealthbridge.ui.components.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
fun CaptureDialog(videoMode: Boolean, onCaptured: suspend (Uri, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val capture = remember { CameraCapture(context) }
    val sensors = remember { SensorMonitor(context) }
    val coach = remember { CaptureCoach(sensors) }
    val view = remember { PreviewView(context) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var available by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var guidance by remember { mutableStateOf("") }
    val proximity by sensors.proximity.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) error = "Camera permission denied. Answer in text instead."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sensors.start()
                capture.setCovered(false)
            }
            if (event == Lifecycle.Event.ON_STOP) {
                sensors.stop()
                capture.setCovered(true)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) sensors.start()
        onDispose { lifecycle.lifecycle.removeObserver(observer); sensors.stop(); capture.close() }
    }
    LaunchedEffect(granted) {
        if (granted) {
            available = capture.bind(lifecycle, view)
            if (!available) error = "Camera unavailable. Answer in text instead."
        }
    }
    LaunchedEffect(proximity) { capture.setCovered(coach.checkProximity() == "COVERED") }
    LaunchedEffect(Unit) {
        while (isActive) {
            guidance = "Steadiness: ${coach.checkSteadiness()} | Light: ${coach.checkLighting()} | Proximity: ${coach.checkProximity()}"
            delay(250)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AhbSectionHeading(if (videoMode) "Capture video" else "Capture photo",
                    if (videoMode) "Up to 30 seconds · No audio · Local storage" else "Evidence stays on this phone",
                    if (videoMode) Icons.Default.Videocam else Icons.Default.PhotoCamera)
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(240.dp)
                    .clip(MaterialTheme.shapes.large))
                AhbCard(title = "Capture coaching") { Text(guidance) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!granted) Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                if (busy) {
                    CircularProgressIndicator()
                    Text("Capturing...")
                }
                AhbPrimaryButtonWide(if (videoMode) "Record video" else "Take photo", loading = busy,
                    enabled = available && !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        var uri: Uri? = null
                        try {
                            require(coach.checkProximity() != "COVERED") { "Uncover the phone before capture." }
                            uri = if (videoMode) capture.recordVideo(30) else capture.capturePhoto()
                            val captured = requireNotNull(uri) { "Capture failed. Try again or answer in text." }
                            onCaptured(captured, if (videoMode) "video/mp4" else "image/jpeg")
                            onDismiss()
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Throwable) {
                            error = failure.message ?: "Capture failed. Answer in text instead."
                            Log.w("AhbCamera", "Capture/attachment failed", failure)
                            uri?.path?.let { java.io.File(it).delete() }
                        } finally { busy = false }
                    }
                })
                AhbSecondaryButton("Cancel / use text", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
