package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

class CtcDecoder internal constructor(private val vocab: List<String>) {
    constructor(context: Context, language: String) : this(readVocabulary(context, language)) {
        Log.i("AhbAsr", "CtcDecoder loaded vocab: ${vocab.size} tokens for $language")
    }

    init {
        require(vocab.isNotEmpty() && vocab.first() == "<unk>") { "Expected an IndicConformer vocabulary beginning with <unk>." }
    }

    internal fun blankIndexFor(classCount: Int): Int = when (classCount) {
        vocab.size + 1 -> vocab.size
        vocab.size -> 0
        else -> throw IllegalArgumentException("CTC classes=$classCount do not match vocab=${vocab.size}.")
    }

    fun decode(logprobs: Array<FloatArray>): String {
        if (logprobs.isEmpty()) return ""
        val width = logprobs.first().size
        val blankIndex = blankIndexFor(width)
        val output = StringBuilder()
        var previous = -1
        for (frame in logprobs) {
            require(frame.size == width) { "CTC output has inconsistent frame widths." }
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (index in frame.indices) {
                val value = frame[index]
                require(!value.isNaN() && value != Float.POSITIVE_INFINITY) { "CTC output contains invalid scores." }
                if (value > bestValue) {
                    bestValue = value
                    best = index
                }
            }
            require(bestValue != Float.NEGATIVE_INFINITY) { "CTC frame has no usable scores." }
            if (best != previous && best != blankIndex) {
                val token = vocab[best]
                if (token != "<unk>") output.append(token)
            }
            previous = best
        }
        return output.toString().replace('▁', ' ').trim()
    }

    private companion object {
        fun readVocabulary(context: Context, language: String): List<String> {
            require(language.matches(Regex("[a-z]{2,3}"))) { "Invalid ASR language code." }
            val file = File(context.filesDir, "models/indicconformer-fp32/$language/vocab.json")
            val array = JSONArray(file.readText(Charsets.UTF_8))
            return List(array.length()) { index ->
                requireNotNull(array.get(index) as? String) { "Vocabulary entries must be strings." }
            }
        }
    }
}
