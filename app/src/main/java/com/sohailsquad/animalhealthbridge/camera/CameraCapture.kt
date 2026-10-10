package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class CameraCapture(context: Context) {
    private val context = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var image: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var paused = false

    suspend fun bind(owner: LifecycleOwner, view: PreviewView): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({
                    runCatching { future.get() }.fold(
                        { if (continuation.isActive) continuation.resume(it) },
                        { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) })
                }, executor)
            }
            require(cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
            val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val photo = ImageCapture.Builder().build()
            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build()
            val movie = VideoCapture.withOutput(recorder)
            val videoAvailable = try {
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, photo, movie)
                true
            } catch (failure: Throwable) {
                Log.w("AhbCamera", "Video unavailable; trying photo-only capture", failure)
                cameraProvider.unbind(cameraPreview, photo, movie)
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, photo)
                false
            }
            provider = cameraProvider
            preview = cameraPreview
            image = photo
            video = if (videoAvailable) movie else null
            Log.i("AhbCamera", "Photo/video camera bound")
            true
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            Log.w("AhbCamera", "Camera unavailable; use text", failure)
            false
        }
    }

    private fun output(extension: String): File {
        val directory = File(context.filesDir, "evidence")
        require(directory.isDirectory || directory.mkdirs())
        return File(directory, UUID.randomUUID().toString() + extension)
    }

    suspend fun capturePhoto(): Uri? = withContext(Dispatchers.Main.immediate) {
        val camera = image ?: return@withContext null
        var file: File? = null
        try {
            val destination = output(".jpg")
            file = destination
            suspendCancellableCoroutine { continuation ->
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(destination).build(), executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            if (continuation.isActive) {
                                Log.i("AhbCamera", "Photo captured")
                                continuation.resume(Uri.fromFile(destination))
                            } else destination.delete()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            destination.delete()
                            Log.w("AhbCamera", "Photo failed", exception)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbCamera", "Photo unavailable", failure)
            null
        }
    }

    suspend fun recordVideo(maxDurationSec: Int): Uri? = withContext(Dispatchers.Main.immediate) {
        val camera = video ?: return@withContext null
        if (recording != null) return@withContext null
        var file: File? = null
        try {
            val destination = output(".mp4")
            file = destination
            suspendCancellableCoroutine { continuation ->
                val timeout = scope.launch {
                    delay(maxDurationSec.coerceIn(1, 30) * 1000L)
                    recording?.stop()
                }
                recording = camera.output.prepareRecording(context, FileOutputOptions.Builder(destination)
                    .setFileSizeLimit(50L * 1024 * 1024).build()).start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        timeout.cancel()
                        recording?.close()
                        recording = null
                        paused = false
                        val result = if (!event.hasError() && destination.length() > 0) Uri.fromFile(destination) else null
                        if (result == null || !continuation.isActive) destination.delete()
                        if (continuation.isActive) continuation.resume(result)
                        Log.i("AhbCamera", "Video finalized error=${event.error}")
                    }
                }
                continuation.invokeOnCancellation {
                    executor.execute { runCatching { recording?.stop() } }
                }
                Log.i("AhbCamera", "Silent video started")
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbCamera", "Video unavailable", failure)
            null
        }
    }

    fun setCovered(covered: Boolean) {
        runCatching {
            if (recording != null && covered != paused) {
                if (covered) recording?.pause() else recording?.resume()
                paused = covered
                Log.i("AhbCamera", "Proximity pause=$covered")
            }
        }.onFailure { Log.w("AhbCamera", "Pause failed", it) }
    }
    fun close() {
        runCatching { recording?.stop() }
        scope.cancel()
        runCatching {
            listOfNotNull(preview, image, video).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
        }.onFailure { Log.w("AhbCamera", "Camera release failed", it) }
        image = null
        video = null
        Log.i("AhbCamera", "Camera closed")
    }
}
