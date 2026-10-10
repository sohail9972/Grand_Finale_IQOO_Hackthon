package com.sohailsquad.animalhealthbridge.ml.translation

import org.json.JSONObject
import java.text.Normalizer

internal class UnigramTokenizer(json: JSONObject) {
    private val model = json.getJSONObject("model").also { require(it.getString("type") == "Unigram") }
    private val vocabulary = model.getJSONArray("vocab")
    private val pieces = (0 until vocabulary.length()).associate { index ->
        vocabulary.getJSONArray(index).let { it.getString(0) to (index.toLong() to it.getDouble(1)) }
    }
    private val addedTokens = json.optJSONArray("added_tokens")?.let { added ->
        (0 until added.length()).associate { index -> added.getJSONObject(index).let {
            it.getString("content") to it.getLong("id")
        } }
    }.orEmpty()
    private val normalization = if (json.optJSONObject("normalizer")?.optString("type") == "NFC")
        Normalizer.Form.NFC else Normalizer.Form.NFKC
    init {
        require(!model.optBoolean("byte_fallback", false)) { "Byte-fallback tokenizer is not supported." }
        val normalizer = json.optJSONObject("normalizer")
        require(normalizer == null || normalizer.optString("type") in setOf("NFKC", "NFC")) {
            "Install an export with an NFKC/NFC Unigram tokenizer."
        }
        val preTokenizer = json.optJSONObject("pre_tokenizer")
        require(preTokenizer == null || preTokenizer.optString("type") == "Metaspace")
    }

    fun encode(text: String): LongArray {
        val normalized = "▁" + Normalizer.normalize(text.trim(), normalization).replace(Regex("\\s+"), "▁")
        val scores = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(scores.size) { -1 }
        val tokens = LongArray(scores.size)
        scores[0] = 0.0
        val maximum = pieces.keys.maxOf { it.length }
        for (start in normalized.indices) {
            if (!scores[start].isFinite()) continue
            for (end in start + 1..minOf(normalized.length, start + maximum)) {
                val piece = pieces[normalized.substring(start, end)] ?: continue
                val score = scores[start] + piece.second
                if (score > scores[end]) {
                    scores[end] = score
                    previous[end] = start
                    tokens[end] = piece.first
                }
            }
        }
        require(previous.last() >= 0) { "Tokenizer cannot encode the input without losing characters." }
        val result = mutableListOf<Long>()
        var position = normalized.length
        while (position > 0) {
            result += tokens[position]
            position = previous[position]
            require(position >= 0)
        }
        return result.asReversed().toLongArray()
    }

    fun languageId(tag: String): Long = requireNotNull(addedTokens[tag] ?: pieces[tag]?.first) {
        "Tokenizer is missing language token $tag."
    }
    fun decode(tokens: List<Long>): String = tokens.joinToString("") { id ->
        if (id in addedTokens.values) "" else vocabulary.getJSONArray(id.toInt()).getString(0)
    }.replace("▁", " ").trim()
}
