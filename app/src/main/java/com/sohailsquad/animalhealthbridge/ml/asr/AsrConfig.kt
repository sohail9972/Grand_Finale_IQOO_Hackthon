package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import java.io.File

object AsrConfig {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 30
    val LANGUAGE_CODES = setOf("kn", "ta", "te", "ml", "mr", "bn", "gu", "pa")

    fun directory(context: Context, language: String = "kn"): File {
        require(language in LANGUAGE_CODES) { "Unsupported IndicConformer language: $language" }
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "models/indicconformer/$language")
    }
    fun model(context: Context, language: String = "kn") = File(directory(context, language), "model.int8.onnx")
    fun tokens(context: Context, language: String = "kn") = File(directory(context, language), "tokens.txt")
}
