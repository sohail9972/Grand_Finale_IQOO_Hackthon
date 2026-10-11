package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.k2fsa.sherpa.onnx.*
import com.sohailsquad.animalhealthbridge.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class AsrEngine(context: Context) {
    private val context = context.applicationContext
    private val fp32 = Fp32AsrEngine(this.context)
    @Volatile private var listening = false
    @Volatile private var recorder: AudioRecord? = null
    private val mutableStatus = MutableStateFlow("Ready")
    private val captureLock = Mutex()
    val status = mutableStatus.asStateFlow()
    fun isAvailable(language: String = "kn"): Boolean = when {
        language == "en" -> AsrConfig.isMoonshineAvailable(context)
        language in AsrConfig.LANGUAGE_CODES -> fp32.isAvailable(language) || hasInt8Model(language)
        else -> false
    }

    private fun hasInt8Model(language: String): Boolean = runCatching {
        if (language == "en" || language !in AsrConfig.LANGUAGE_CODES) return@runCatching false
        listOf(AsrConfig.model(context, language), AsrConfig.tokens(context, language)).all {
            it.isFile && it.canRead() && it.length() > 0
        }
    }.onFailure { Log.w("AhbAsr", "Speech model storage unavailable", it) }.getOrDefault(false)

    suspend fun listen(
        language: String? = null,
        onPartial: (String) -> Unit = {},
    ): Result<String> = startListening(onPartial = onPartial, onFinal = {}, language = language)

    suspend fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        language: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            if (!captureLock.tryLock()) {
                return@withContext Result.failure(IllegalStateException("Speech capture is already active."))
            }
            listening = true
            var recognizer: OfflineRecognizer? = null
            try {
                val code = language ?: UserPreferences(context).selectedLanguage()
                require(code in AsrConfig.LANGUAGE_CODES) { "No on-device speech model configured for $code." }
                mutableStatus.value = "Loading speech model..."
                Log.i("AhbAsr", "Resolving input language=$code; ${if (code == "en") "using Moonshine English" else "trying FP32 first"}")
                val useFp32 = code != "en" && fp32.isAvailable(code) && fp32.load(code)
                currentCoroutineContext().ensureActive()
                if (!listening) throw CancellationException("Speech capture stopped during model loading")
                if (code == "en") {
                    val preprocessFile = AsrConfig.moonshinePreprocess(context)
                    val encoderFile = AsrConfig.moonshineEncode(context)
                    val uncachedDecoderFile = AsrConfig.moonshineUncachedDecode(context)
                    val cachedDecoderFile = AsrConfig.moonshineCachedDecode(context)
                    val tokensFile = AsrConfig.moonshineTokens(context)
                    if (!AsrConfig.isMoonshineAvailable(context)) {
                        mutableStatus.value = "Voice input is not set up on this phone yet. Type the symptoms below - it works exactly the same."
                        Log.i("AhbAsr", "Missing or unreadable Moonshine files: preprocess=$preprocessFile encoder=$encoderFile uncachedDecoder=$uncachedDecoderFile cachedDecoder=$cachedDecoderFile tokens=$tokensFile; AndroidAsr fallback required")
                        return@withContext Result.failure(IllegalStateException("Moonshine English files unavailable."))
                    }
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = AsrConfig.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            moonshine = OfflineMoonshineModelConfig(
                                preprocessor = preprocessFile.path,
                                encoder = encoderFile.path,
                                uncachedDecoder = uncachedDecoderFile.path,
                                cachedDecoder = cachedDecoderFile.path
                            ),
                            tokens = tokensFile.path,
                            numThreads = 2,
                            provider = "cpu"
                        )
                    ))
                    Log.i("AhbAsr", "Moonshine English loaded")
                } else if (!useFp32) {
                    val modelFile = AsrConfig.model(context, code)
                    val tokensFile = AsrConfig.tokens(context, code)
                    Log.i("AhbAsr", "FP32 unavailable for $code; checking legacy sherpa INT8")
                    if (!hasInt8Model(code)) {
                        mutableStatus.value = "Voice input is not set up on this phone yet. Type the symptoms below - it works exactly the same."
                        Log.i("AhbAsr", "Missing or unreadable ASR files: model=$modelFile tokens=$tokensFile; AndroidAsr fallback required")
                        return@withContext Result.failure(IllegalStateException("IndicConformer files unavailable for $code."))
                    }
                    Log.i("AhbAsr", "Loading IndicConformer lang=$code model=$modelFile tokens=$tokensFile")
                    OrtSession.SessionOptions().use { options ->
                        OrtEnvironment.getEnvironment().createSession(modelFile.path, options).use { probe ->
                            val metadata = probe.metadata.customMetadata
                            require(metadata["model_type"] == "nemo_ctc") { "A sherpa-compatible NeMo CTC export is required." }
                            metadata["language"]?.let { modelLanguage ->
                                require(modelLanguage.substringBefore('-').equals(code, ignoreCase = true)) {
                                    "This speech model does not support $code."
                                }
                            }
                            metadata["normalize_type"]?.let { normalization ->
                                require(normalization == "per_feature") { "Expected per_feature normalization in the model export." }
                            }
                            Log.i("AhbAsr", "CTC metadata: normalize_type=${metadata["normalize_type"] ?: "not reported"}; export unchanged")
                        }
                    }
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = AsrConfig.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(
                            model = modelFile.path), tokens = tokensFile.path,
                            numThreads = 2, provider = "cpu", modelType = "nemo_ctc")))
                }
                currentCoroutineContext().ensureActive()
                if (!listening) throw CancellationException("Speech capture stopped during model loading")
                if (code != "en") Log.i("AhbAsr", "IndicConformer loaded lang=$code; backend=${if (useFp32) "FP32 ONNX" else "sherpa INT8 nemo_ctc"}")
                val minimum = AudioRecord.getMinBufferSize(AsrConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0) { "Microphone unavailable." }
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AsrConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
                recorder = audio
                require(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable." }
                audio.startRecording()
                mutableStatus.value = "Listening"
                val samples = FloatArray(AsrConfig.SAMPLE_RATE * AsrConfig.MAX_SECONDS)
                val chunk = ShortArray(2048)
                var size = 0
                val asr = recognizer
                coroutineScope {
                    val partialJob = if (asr != null) launch(Dispatchers.Default) {
                        while (isActive && listening) {
                            delay(3000)
                            if (!listening) break
                            val snapshot = synchronized(samples) { samples.copyOf(size) }
                            if (snapshot.isNotEmpty()) {
                                val partial = decode(asr, snapshot)
                                if (partial.isNotBlank()) withContext(Dispatchers.Main.immediate) { onPartial(partial) }
                            }
                        }
                    } else null
                    try {
                        while (listening && currentCoroutineContext().isActive && size < samples.size) {
                            val count = audio.read(chunk, 0, minOf(chunk.size, samples.size - size))
                            if (count <= 0) {
                                if (listening) error("Microphone read failed ($count).")
                                break
                            }
                            synchronized(samples) {
                                repeat(count) { offset -> samples[size + offset] = chunk[offset] / 32768f }
                                size += count
                            }
                        }
                    } finally {
                        listening = false
                        runCatching { audio.stop() }
                        mutableStatus.value = "Transcribing..."
                        withContext(NonCancellable) { partialJob?.cancelAndJoin() }
                    }
                }
                currentCoroutineContext().ensureActive()
                val final = if (size > 0) {
                    val captured = samples.copyOf(size)
                    if (useFp32) fp32.transcribe(captured, code) else decode(requireNotNull(recognizer), captured)
                } else ""
                currentCoroutineContext().ensureActive()
                require(final.isNotBlank()) {
                    if (code == "en") "No speech recognized by Moonshine." else "No speech recognized by IndicConformer."
                }
                withContext(Dispatchers.Main.immediate) { onFinal(final) }
                mutableStatus.value = "Finished"
                Log.i("AhbAsr", "Capture finished samples=$size characters=${final.length}")
                if (code == "en") Log.i("AhbAsr", "Moonshine transcription=$final")
                Result.success(final)
            } catch (failure: CancellationException) {
                mutableStatus.value = "Ready"
                throw failure
            } catch (failure: Throwable) {
                mutableStatus.value = "Voice input unavailable. Type the symptoms instead."
                Log.w("AhbAsr", "Speech capture unavailable; AndroidAsr fallback required", failure)
                Result.failure(failure)
            } finally {
                listening = false
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                runCatching { recognizer?.release() }
                fp32.release()
                captureLock.unlock()
            }
        }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AsrConfig.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    fun stopListening() {
        listening = false
        runCatching { recorder?.stop() }.onFailure { Log.w("AhbAsr", "Microphone stop failed", it) }
        Log.i("AhbAsr", "Stop requested")
    }
    fun close() = stopListening()
}
