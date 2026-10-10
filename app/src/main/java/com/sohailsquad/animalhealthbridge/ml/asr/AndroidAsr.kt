package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidAsr(context: Context) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isRecognitionAvailable(context) &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (failure: Throwable) {
        Log.w(TAG, "Speech service availability check failed", failure)
        false
    }

    suspend fun listen(languageTag: String = "hi-IN"): Result<String> = withContext(Dispatchers.Main.immediate) {
        if (activeRecognizer != null) return@withContext errorResult(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        var recognizer: SpeechRecognizer? = null
        try {
            if (!isAvailable()) {
                Log.i(TAG, "On-device speech service unavailable; use text input")
                return@withContext errorResult(SpeechRecognizer.ERROR_CLIENT)
            }
            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = speech
            activeRecognizer = speech
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            withTimeoutOrNull(45_000L) {
                suspendCancellableCoroutine<Result<String>> { continuation ->
                    speech.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onResults(results: Bundle?) {
                            if (!continuation.isActive) return
                            val result = try {
                                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()?.trim().orEmpty()
                                if (text.isBlank()) errorResult(SpeechRecognizer.ERROR_NO_MATCH)
                                else {
                                    Log.i(TAG, "result=$text")
                                    Result.success(text)
                                }
                            } catch (failure: Throwable) {
                                Log.w(TAG, "Speech result could not be read", failure)
                                Result.failure(failure)
                            }
                            continuation.resume(result)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(errorResult(error))
                        }
                    })
                    Log.i(TAG, "listening lang=$languageTag")
                    speech.startListening(intent)
                }
            } ?: errorResult(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        } catch (failure: CancellationException) {
            Log.i(TAG, "Speech capture cancelled")
            throw failure
        } catch (failure: Throwable) {
            Log.w(TAG, "Speech capture failed", failure)
            Result.failure(failure)
        } finally {
            activeRecognizer = null
            recognizer?.let { speech ->
                runCatching { speech.cancel() }.onFailure { Log.w(TAG, "Speech cancel failed", it) }
                runCatching { speech.destroy() }.onFailure { Log.w(TAG, "Speech cleanup failed", it) }
            }
        }
    }

    fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopListening() }
            return
        }
        runCatching { activeRecognizer?.stopListening() }
            .onFailure { Log.w(TAG, "Speech stop failed", it) }
    }

    private fun errorResult(code: Int): Result<String> {
        Log.w(TAG, "error=$code")
        return Result.failure(Exception(code.toString()))
    }

    private companion object { const val TAG = "AhbAsr" }
}
