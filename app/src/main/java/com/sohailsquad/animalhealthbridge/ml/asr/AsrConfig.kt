package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.util.Log
import java.io.File

object AsrConfig {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 30
    const val ENGLISH_MODEL_DIR = "whisper-tiny-en"
    const val MOONSHINE_DIR = "moonshine-tiny-en"
    val LANGUAGE_CODES = setOf("en", "hi", "kn", "ta", "te", "ml", "mr", "bn", "gu", "pa")

    fun directory(context: Context, language: String = "kn"): File {
        require(language in LANGUAGE_CODES && language != "en") { "Unsupported IndicConformer language: $language" }
        val dir = File(context.filesDir, "models/indicconformer/$language")
        Log.i("AhbAsr", "ASR dir for $language: ${dir.absolutePath}")
        return dir
    }
    fun model(context: Context, language: String = "kn") = File(directory(context, language), "model.int8.onnx")
    fun tokens(context: Context, language: String = "kn") = File(directory(context, language), "tokens.txt")
    fun whisperEncoder(context: Context): File = File(context.filesDir, "models/$ENGLISH_MODEL_DIR/tiny.en-encoder.onnx")
    fun whisperDecoder(context: Context): File = File(context.filesDir, "models/$ENGLISH_MODEL_DIR/tiny.en-decoder.onnx")
    fun whisperTokens(context: Context): File = File(context.filesDir, "models/$ENGLISH_MODEL_DIR/tiny.en-tokens.txt")
    fun moonshinePreprocess(context: Context): File = File(context.filesDir, "models/$MOONSHINE_DIR/preprocess.onnx")
    fun moonshineEncode(context: Context): File = File(context.filesDir, "models/$MOONSHINE_DIR/encode.int8.onnx")
    fun moonshineUncachedDecode(context: Context): File = File(context.filesDir, "models/$MOONSHINE_DIR/uncached_decode.int8.onnx")
    fun moonshineCachedDecode(context: Context): File = File(context.filesDir, "models/$MOONSHINE_DIR/cached_decode.int8.onnx")
    fun moonshineTokens(context: Context): File = File(context.filesDir, "models/$MOONSHINE_DIR/tokens.txt")
    fun isMoonshineAvailable(context: Context): Boolean = runCatching {
        listOf(moonshinePreprocess(context), moonshineEncode(context), moonshineUncachedDecode(context),
            moonshineCachedDecode(context), moonshineTokens(context)).all {
            it.isFile && it.canRead() && it.length() > 0
        }
    }.onFailure { Log.w("AhbAsr", "Moonshine model storage unavailable", it) }.getOrDefault(false)
}
