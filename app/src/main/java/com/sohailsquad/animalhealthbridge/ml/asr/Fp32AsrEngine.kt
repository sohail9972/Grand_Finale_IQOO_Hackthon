package com.sohailsquad.animalhealthbridge.ml.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class Fp32AsrEngine(context: Context) {
    private val context = context.applicationContext
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var decoder: CtcDecoder? = null
    private var currentLang: String? = null
    private val preprocessor = NemoPreprocessor()

    fun isAvailable(language: String): Boolean = try {
        if (!language.matches(Regex("[a-z]{2,3}"))) false else {
            val directory = directory(language)
            listOf(File(directory, "model.onnx"), File(directory, "vocab.json")).all {
                it.isFile && it.canRead() && it.length() > 0
            }
        }
    } catch (failure: Throwable) {
        Log.w("AhbAsr", "FP32 model storage unavailable for $language", failure)
        false
    }

    @Synchronized
    fun load(language: String): Boolean {
        if (session != null && currentLang == language) return true
        release()
        if (!isAvailable(language)) {
            Log.i("AhbAsr", "FP32 model not present for $language")
            return false
        }
        var created: OrtSession? = null
        return try {
            val runtime = OrtEnvironment.getEnvironment()
            val model = File(directory(language), "model.onnx")
            Log.i("AhbAsr", "Loading FP32 IndicConformer lang=$language path=${model.absolutePath}")
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                created = runtime.createSession(model.absolutePath, options)
            }
            val active = requireNotNull(created)
            require(active.inputNames == setOf("audio_signal", "length")) { "Unexpected FP32 input names: ${active.inputNames}" }
            val audio = active.inputInfo["audio_signal"]?.info as? TensorInfo
            val length = active.inputInfo["length"]?.info as? TensorInfo
            val output = active.outputInfo["logprobs"]?.info as? TensorInfo
            require(audio != null && audio.type == OnnxJavaType.FLOAT && audio.shape.size == 3 &&
                audio.shape[1] == 80L && (audio.shape[0] < 0 || audio.shape[0] == 1L)) { "Expected float32 audio_signal [batch,80,time]." }
            require(length != null && length.type == OnnxJavaType.INT64 && length.shape.size == 1 &&
                (length.shape[0] < 0 || length.shape[0] == 1L)) { "Expected int64 length [batch]." }
            require(output != null && output.type == OnnxJavaType.FLOAT && output.shape.size == 3) {
                "Expected float32 logprobs [batch,time,vocab]."
            }
            val loadedDecoder = CtcDecoder(context, language)
            if (output.shape[2] > 0) {
                val blank = loadedDecoder.blankIndexFor(output.shape[2].toInt())
                Log.i("AhbAsr", "FP32 CTC classes=${output.shape[2]} blankIndex=$blank")
            }
            env = runtime
            session = active
            decoder = loadedDecoder
            currentLang = language
            created = null
            Log.i("AhbAsr", "FP32 IndicConformer loaded for $language; preprocessing=per_feature")
            true
        } catch (failure: Throwable) {
            Log.e("AhbAsr", "FP32 load failed for $language", failure)
            runCatching { created?.close() }.onFailure { Log.w("AhbAsr", "FP32 session cleanup failed", it) }
            release()
            false
        }
    }

    @Synchronized
    fun transcribe(samples: FloatArray, language: String): String {
        check(load(language)) { "FP32 model not loaded for $language" }
        require(samples.size <= AsrConfig.SAMPLE_RATE * AsrConfig.MAX_SECONDS) { "PCM exceeds the 30-second capture limit." }
        val runtime = requireNotNull(env)
        val active = requireNotNull(session)
        val activeDecoder = requireNotNull(decoder)
        val started = SystemClock.elapsedRealtime()
        val mel = preprocessor.preprocess(samples)
        val frames = mel[0].size
        Log.i("AhbAsr", "FP32 mel 80 x $frames; PCM samples=${samples.size}")
        val buffer = FloatBuffer.allocate(MelFilterbank.N_MELS * frames)
        for (melIndex in mel.indices) {
            for (frameIndex in 0 until frames) buffer.put(melIndex * frames + frameIndex, mel[melIndex][frameIndex])
        }
        OnnxTensor.createTensor(runtime, buffer, longArrayOf(1L, 80L, frames.toLong())).use { audio ->
            OnnxTensor.createTensor(runtime, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1L)).use { length ->
                Log.i("AhbAsr", "Running FP32 inference lang=$language")
                active.run(mapOf("audio_signal" to audio, "length" to length)).use { output ->
                    val batch = output.get("logprobs").orElseThrow { IllegalStateException("FP32 logprobs missing.") }.value as? Array<*>
                    require(batch != null && batch.size == 1) { "FP32 output batch must have size one." }
                    val rawFrames = batch[0] as? Array<*>
                    require(rawFrames != null && rawFrames.isNotEmpty()) { "FP32 output has no frames." }
                    val scores = Array(rawFrames.size) { index ->
                        requireNotNull(rawFrames[index] as? FloatArray) { "FP32 output must contain float32 scores." }
                    }
                    val transcript = activeDecoder.decode(scores)
                    Log.i("AhbAsr", "FP32 CTC decoded frames=${scores.size} characters=${transcript.length} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    return transcript
                }
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { session?.close() }.onFailure { Log.w("AhbAsr", "FP32 release failed", it) }
        session = null
        env = null
        decoder = null
        currentLang = null
    }

    private fun directory(language: String) = File(context.filesDir, "models/indicconformer-fp32/$language")
}
