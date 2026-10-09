package com.sohailsquad.animalhealthbridge.nfc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sohailsquad.animalhealthbridge.ui.components.AhbPrimaryButtonWide
import com.sohailsquad.animalhealthbridge.ui.components.AhbSecondaryButton
import com.sohailsquad.animalhealthbridge.ui.theme.LocalAhbDimens
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera permission string, hoisted so the request and the check cannot drift.
 */
private const val CAMERA_PERMISSION = Manifest.permission.CAMERA

/**
 * Barcode formats we accept.
 *
 * QR covers the printed codes we generate; EAN-13 and Code-128 are included
 * because some ear tags and medicine boxes already carry them, and accepting
 * them costs nothing. Narrowing the format list is also a real speed win - the
 * scanner skips the symbologies we cannot use.
 */
private val SUPPORTED_FORMATS = listOf(
    Barcode.FORMAT_QR_CODE,
    Barcode.FORMAT_EAN_13,
    Barcode.FORMAT_CODE_128,
)

/**
 * Full-screen QR / barcode scanner.
 *
 * Runs entirely on-device using the bundled ML Kit barcode model, so it works
 * in airplane mode - which is the whole point of this app. Nothing here talks to
 * a server.
 *
 * The dialog degrades rather than failing:
 *   - no camera hardware  -> an explanatory message;
 *   - permission denied   -> a message plus a button to ask again;
 *   - camera bind failure -> a message, and the caller's cancel path still works.
 *
 * @param onResult invoked with the decoded barcode value, on the main thread.
 * @param onCancel invoked when the owner closes the scanner.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(CAMERA_PERMISSION)

    // Ask on first show. Asking from a LaunchedEffect (rather than during
    // composition) keeps the permission dialog from fighting the scanner
    // dialog's own window animation.
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        // usePlatformDefaultWidth = false lets the scanner fill the screen,
        // which matters when the owner is aiming at a small tag in poor light.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(LocalAhbDimens.current.padding),
                verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gap),
            ) {
                Text(
                    text = "Scan QR code",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Point the camera at the code on the tag or label.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        // No camera at all: nothing to bind, so explain and stop.
                        !context.hasCamera() -> ScannerMessage(
                            title = "No camera on this device",
                            body = "Use the NFC scan or type the tag number instead.",
                        )

                        !cameraPermission.status.isGranted -> ScannerMessage(
                            title = "Camera permission needed",
                            body = "The scanner needs camera access. Nothing is uploaded - " +
                                "the code is read on this phone.",
                        )

                        else -> CameraPreview(
                            onBarcode = { value ->
                                Log.i(TAG, "Barcode scanned value=$value")
                                onResult(value)
                            },
                        )
                    }
                }

                if (!cameraPermission.status.isGranted && context.hasCamera()) {
                    AhbPrimaryButtonWide(
                        text = "Allow camera",
                        onClick = { cameraPermission.launchPermissionRequest() },
                        icon = Icons.Filled.CameraAlt,
                    )
                }

                AhbSecondaryButton(
                    text = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Live camera preview wired to the ML Kit barcode analyser.
 *
 * The camera is bound to the current lifecycle owner, so it releases
 * automatically when the owner backgrounds the app instead of holding the
 * sensor open.
 *
 * @param onBarcode invoked once, with the first successfully decoded value.
 */
@Composable
private fun CameraPreview(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Guards against a burst of frames all decoding the same code before the
    // dialog has had a chance to close: without it, one QR code can produce
    // several onResult calls and therefore several navigations.
    val delivered = remember { AtomicBoolean(false) }

    // A single-thread executor for frame analysis. Created once and shut down
    // in onDispose so we do not leak a thread per recomposition.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // The ML Kit scanner is expensive to construct; keep one for the dialog's
    // lifetime and close it when the dialog goes away.
    val scanner = remember {
        BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(SUPPORTED_FORMATS.first(), *SUPPORTED_FORMATS.drop(1).toIntArray())
                .build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                scanner.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close barcode scanner", t)
            }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                // FILL_CENTER keeps the preview looking like a camera rather
                // than letterboxing it, which makes aiming much easier.
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            // Drop stale frames rather than queueing them: on a
                            // slow phone a backlog would make the preview lag
                            // behind what the camera is actually pointing at.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(RESOLUTION_SELECTOR)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val input = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )

                            scanner.process(input)
                                .addOnSuccessListener { barcodes ->
                                    val value = barcodes
                                        .firstOrNull { !it.rawValue.isNullOrBlank() }
                                        ?.rawValue
                                    // compareAndSet makes "first result wins"
                                    // atomic across the analyser thread.
                                    if (value != null && delivered.compareAndSet(false, true)) {
                                        previewView.post { onBarcode(value) }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    // A single bad frame is routine; log it and
                                    // keep scanning rather than tearing down the
                                    // camera.
                                    Log.w(TAG, "Barcode frame failed to decode", error)
                                }
                                // Closing in onComplete runs after either
                                // outcome, which is what lets the next frame in.
                                .addOnCompleteListener { imageProxy.close() }
                        }

                        // Rebind from scratch; bindToLifecycle throws if a use
                        // case is already attached, which happens when this
                        // composable re-enters composition.
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        Log.i(TAG, "Camera bound for barcode scanning")
                    } catch (t: Throwable) {
                        // Camera can be unavailable (in use by another app, or a
                        // vendor ROM refusing access). Log and leave the caller's
                        // cancel path usable instead of crashing.
                        Log.e(TAG, "Failed to bind camera", t)
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to obtain camera provider", t)
            }
        },
    )
}

/**
 * Centred explanation shown when the scanner cannot run.
 *
 * @param title short headline.
 * @param body one-sentence explanation of what to do instead.
 */
@Composable
private fun ScannerMessage(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(LocalAhbDimens.current.gapLoose)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LocalAhbDimens.current.gapTight),
    ) {
        Icon(
            imageVector = Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * True when the device reports any camera.
 *
 * Checked via the package manager rather than by trying to bind, so we can show
 * the right message before touching the camera stack.
 */
private fun Context.hasCamera(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private const val TAG = "AhbQr"

/**
 * Analysis resolution. 1280x720 is plenty for a barcode and keeps per-frame
 * work small; CLOSEST_LOWER_THEN_HIGHER lets a device that cannot do 720p fall
 * back to the nearest smaller size instead of failing the bind.
 */
private val RESOLUTION_SELECTOR: ResolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 720),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
        ),
    )
    .build()