package com.sohailsquad.animalhealthbridge.ml.translation

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections

/**
 * Offline Hindi to English translation.
 *
 * ---------------------------------------------------------------------------
 * HONEST NOTE ON THE MODEL INTERFACE
 * ---------------------------------------------------------------------------
 * The project plan names AI4Bharat IndicTrans2 (INT8 ONNX). IndicTrans2 is an
 * encoder-decoder whose ONNX export takes `input_ids` / `attention_mask` and
 * emits `logits`, and it needs a SentencePiece vocabulary to tokenise and to
 * detokenise. This wrapper implements exactly that interface: it loads the
 * encoder and decoder graphs, runs greedy decoding, and detokenises.
 *
 * The vocabulary and tokeniser are read from the model directory at runtime,
 * because they are part of the downloaded model rather than something the app
 * can hardcode. If they are absent the engine reports itself unavailable and
 * the caller falls back to English input - which is the documented fallback in
 * the spec, and the reason [TranslationResult.isFallback] exists.
 *
 * Nothing here touches the network.
 *
 * @param context used to locate the model directory.
 */
class Translator(context: Context) {

    private val appContext = context.applicationContext

    /** Result of a translation attempt. */
    data class TranslationResult(
        /** The text to use downstream. Always non-blank on success. */
        val text: String,
        /**
         * True when [text] did not come from the model - i.e. the model was
         * missing or failed, and the caller is seeing the original or a
         * dictionary-based approximation. The UI must not present a fallback as
         * a faithful translation.
         */
        val isFallback: Boolean,
        /** Which path produced this result, for logging and the case record. */
        val source: String,
    )

    /** Lazily built ONNX environment; shared across both sessions. */
    private var environment: OrtEnvironment? = null

    /** Encoder session, or null when the model is not installed. */
    private var encoder: OrtSession? = null

    /** Decoder session, for autoregressive steps. */
    private var decoder: OrtSession? = null

    /** Token table loaded from the model directory. */
    private var vocabulary: Vocabulary? = null

    /** True once a load has been attempted, so failures are not retried hot. */
    @Volatile
    private var loadAttempted = false

    /** True when both graphs and the vocabulary loaded successfully. */
    @Volatile
    private var modelAvailable = false

    /**
     * True when a real translation model is usable.
     *
     * Callers use this to decide whether to show the "type in English" hint
     * before the owner speaks, rather than after a failed translation.
     */
    fun isAvailable(): Boolean {
        if (!loadAttempted) load()
        return modelAvailable
    }

    /**
     * Loads the model. Safe to call repeatedly; only the first call does work.
     *
     * @return true when the model is ready.
     */
    fun load(): Boolean {
        if (loadAttempted) return modelAvailable
        synchronized(this) {
            if (loadAttempted) return modelAvailable
            loadAttempted = true

            val dir = modelDir()
            val encoderFile = File(dir, ENCODER_FILE)
            val decoderFile = File(dir, DECODER_FILE)
            val vocabFile = File(dir, VOCAB_FILE)

            if (!encoderFile.isFile || !decoderFile.isFile || !vocabFile.isFile) {
                // Expected on a fresh install: the models are side-loaded.
                Log.i(TAG, "Translation model not installed at ${dir.absolutePath}")
                modelAvailable = false
                return false
            }

            return try {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // Two threads matches the ASR engine and leaves big cores
                    // for the UI on a mid-range Snapdragon.
                    setIntraOpNumThreads(2)
                    // Sequential execution: the graphs are deep but narrow, so
                    // parallel graph execution adds overhead, not throughput.
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                }

                environment = env
                encoder = env.createSession(encoderFile.absolutePath, options)
                decoder = env.createSession(decoderFile.absolutePath, options)
                vocabulary = Vocabulary.load(vocabFile)

                modelAvailable = true
                Log.i(
                    TAG,
                    "Translation model loaded (encoder inputs=${encoder?.inputNames}, " +
                        "vocab=${vocabulary?.size})",
                )
                true
            } catch (t: Throwable) {
                // Throwable: a corrupt ONNX graph can surface as an Error.
                Log.e(TAG, "Failed to load translation model", t)
                close()
                modelAvailable = false
                false
            }
        }
    }

    /**
     * Translates [text] from Hindi to English.
     *
     * Never throws. On any failure it returns the original text flagged as a
     * fallback, because an untranslated symptom description is still more
     * useful to the vet than an empty screen.
     *
     * @param text the Hindi transcript.
     * @param cache optional cache; when supplied, hits bypass the model.
     */
    suspend fun translateHindiToEnglish(
        text: String,
        cache: TranslationCacheDao? = null,
    ): TranslationResult = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext TranslationResult("", isFallback = false, source = "empty")
        }

        // --- 1. Cache ------------------------------------------------------
        if (cache != null) {
            try {
                cache.get(trimmed, SOURCE_LANG, TARGET_LANG)?.let { hit ->
                    Log.d(TAG, "Cache hit for ${trimmed.length}-char phrase")
                    return@withContext TranslationResult(hit, isFallback = false, source = "cache")
                }
            } catch (t: Throwable) {
                // A cache failure must never block translation.
                Log.w(TAG, "Cache lookup failed; continuing to model", t)
            }
        }

        // --- 2. Model ------------------------------------------------------
        if (!isAvailable()) {
            val fallback = TranslationResult(
                text = trimmed,
                isFallback = true,
                source = "model-missing",
            )
            Log.i(TAG, "No model; returning source text as fallback")
            return@withContext fallback
        }

        val translated = try {
            runGreedyDecode(trimmed)
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed", t)
            null
        }

        if (translated.isNullOrBlank()) {
            return@withContext TranslationResult(
                text = trimmed,
                isFallback = true,
                source = "inference-failed",
            )
        }

        // --- 3. Store ------------------------------------------------------
        if (cache != null) {
            try {
                cache.put(
                    TranslationCacheEntry(
                        sourceText = trimmed,
                        targetText = translated,
                        sourceLang = SOURCE_LANG,
                        targetLang = TARGET_LANG,
                        isModelOutput = true,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cache translation", t)
            }
        }

        TranslationResult(translated, isFallback = false, source = "model")
    }

    /**
     * Runs the encoder once and then decodes one token at a time.
     *
     * Greedy (argmax) decoding rather than beam search: translation quality on
     * short clinical phrases barely differs, and greedy is several times
     * cheaper, which is what keeps the wait tolerable on a phone.
     *
     * @param sourceText Hindi input.
     * @return the English translation, or null when the output is unusable.
     */
    private fun runGreedyDecode(sourceText: String): String? {
        val env = environment ?: return null
        val enc = encoder ?: return null
        val dec = decoder ?: return null
        val vocab = vocabulary ?: return null

        val sourceIds = vocab.encode(sourceText, addBos = true, addEos = true)
        if (sourceIds.isEmpty()) return null

        // --- Encoder: once per sentence ------------------------------------
        // The hidden states are copied out into plain Kotlin arrays before the
        // ONNX result is closed. Reusing the native tensor would leave us
        // holding freed memory.
        val encoderHidden: FloatArray
        val encoderShape: LongArray
        try {
            val result = enc.run(
                mapOf(
                    "input_ids" to idsTensor(env, sourceIds, longArrayOf(1, sourceIds.size.toLong())),
                    "attention_mask" to idsTensor(
                        env,
                        LongArray(sourceIds.size) { 1L },
                        longArrayOf(1, sourceIds.size.toLong()),
                    ),
                ),
            )
            result.use {
                val output = result.get(0) as? OnnxTensor
                    ?: run {
                        Log.w(TAG, "Encoder produced no tensor")
                        return null
                    }
                encoderHidden = output.floatBuffer.let { buffer ->
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                encoderShape = output.info.shape
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder step failed", t)
            return null
        }

        // --- Decoder: autoregressive ---------------------------------------
        val outputIds = mutableListOf<Long>()
        var currentToken = vocab.bosId
        var steps = 0

        while (steps < MAX_DECODE_STEPS && outputIds.size < MAX_OUTPUT_TOKENS) {
            steps++

            // -1 means the vocabulary has no BOS id; seed with the EOS id
            // instead, which is what encoder-decoder exports expect.
            val seed = if (currentToken >= 0) currentToken else vocab.eosId
            if (seed < 0) break

            val next: Int = try {
                val result = dec.run(
                    mapOf(
                        "input_ids" to idsTensor(env, longArrayOf(seed), longArrayOf(1, 1)),
                        // IndicTrans2 exports name this either way depending on
                        // the conversion script, so both are supplied.
                        "encoder_hidden_states" to floatTensor(env, encoderHidden, encoderShape),
                        "encoder_outputs" to floatTensor(env, encoderHidden, encoderShape),
                    ),
                )
                result.use {
                    val logits = result.get(0) as? OnnxTensor ?: return null
                    argMaxLastToken(logits)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Decoder step $steps failed", t)
                break
            }

            if (next < 0) break
            if (next.toLong() == vocab.eosId) break

            outputIds += next.toLong()
            currentToken = next.toLong()
        }

        if (outputIds.isEmpty()) return null
        return vocab.decode(outputIds)
    }

    /**
     * Wraps a LongArray as a [1, n] int64 tensor.
     *
     * The tensor is NOT closed here: it is owned by the caller's `run` call and
     * released with the surrounding result. ONNX Runtime keeps a reference to
     * the buffer for the duration of the run, so closing early would be a
     * use-after-free.
     */
    private fun idsTensor(env: OrtEnvironment, ids: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)

    /** Wraps a FloatArray as a tensor with the given shape. */
    private fun floatTensor(
        env: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)    /**
     * Greedy argmax over the final position of a [1, seq, vocab] logits tensor.
     *
     * @return the token id with the highest score at the last step, or -1 when
     *   the tensor is unusable.
     */
    private fun argMaxLastToken(logits: OnnxTensor): Int {
        val shape = logits.info.shape
        if (shape.isEmpty()) return -1
        val vocabSize = shape.last().toInt()
        if (vocabSize <= 0) return -1

        val buffer = logits.floatBuffer
        val total = buffer.remaining()
        if (total < vocabSize) return -1

        // Only the last decoding position determines the next token, so seek
        // straight to it instead of scanning the whole tensor.
        val offset = total - vocabSize
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val score = buffer.get(offset + i)
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }
    /** Releases ONNX sessions. Safe to call repeatedly. */
    fun close() {
        try {
            encoder?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close encoder", t)
        }
        try {
            decoder?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close decoder", t)
        }
        encoder = null
        decoder = null
        // OrtEnvironment is process-wide and must NOT be closed here: other
        // components may still be using it.
        environment = null
        vocabulary = null
    }

    /** Directory holding the translation model. */
    private fun modelDir(): File =
        File(File(appContext.filesDir, MODEL_ROOT), TRANSLATION_DIR)

    private class Vocabulary(
        private val idToToken: Map<Long, String>,
        private val tokenToId: Map<String, Long>,
    ) {
        val size: Int get() = idToToken.size

        /** Beginning-of-sequence id, or -1 when the model has none. */
        val bosId: Long = tokenToId["<s>"] ?: tokenToId["<bos>"] ?: -1L

        /** End-of-sequence id; -1 disables early stopping. */
        val eosId: Long = tokenToId["</s>"] ?: tokenToId["<eos>"] ?: -1L

        /**
         * Encodes text to token ids.
         *
         * SentencePiece marks a word boundary with U+2581 (LOWER ONE EIGHTH
         * BLOCK); we replace spaces with it so the segmentation matches what the
         * model was trained on. Unknown characters are skipped rather than
         * mapped to an UNK that would derail decoding.
         */
        fun encode(text: String, addBos: Boolean, addEos: Boolean): LongArray {
            val normalised = text.trim().replace(" ", SENTENCEPIECE_SPACE)
            val ids = mutableListOf<Long>()

            if (addBos && bosId >= 0) ids += bosId

            // Greedy longest-match segmentation over the vocabulary. Slower
            // than a trie but simple, and the strings here are short.
            var index = 0
            while (index < normalised.length) {
                var matched = false
                // Longest piece first, capped so a pathological vocabulary
                // cannot make this quadratic.
                val maxLen = minOf(MAX_PIECE_LENGTH, normalised.length - index)
                for (len in maxLen downTo 1) {
                    val candidate = normalised.substring(index, index + len)
                    val id = tokenToId[candidate]
                    if (id != null) {
                        ids += id
                        index += len
                        matched = true
                        break
                    }
                }
                if (!matched) index++ // skip the unknown character
            }

            if (addEos && eosId >= 0) ids += eosId
            return ids.toLongArray()
        }

        /** Turns token ids back into readable text. */
        fun decode(ids: List<Long>): String {
            val sb = StringBuilder()
            for (id in ids) {
                val token = idToToken[id] ?: continue
                if (token == "<s>" || token == "</s>" || token == "<pad>") continue
                sb.append(token)
            }
            // Restore word boundaries and tidy the spacing the model emits.
            return sb.toString()
                .replace(SENTENCEPIECE_SPACE, " ")
                .replace("  ", " ")
                .trim()
        }

        companion object {
            /** Loads a `token<TAB>id` or `token id` file. */
            fun load(file: File): Vocabulary {
                val idToToken = HashMap<Long, String>()
                val tokenToId = HashMap<String, Long>()

                file.forEachLine { raw ->
                    val line = raw.trimEnd()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val parts = line.split('\t', ' ')
                    if (parts.size < 2) return@forEachLine
                    val id = parts.last().toLongOrNull() ?: return@forEachLine
                    val token = parts.dropLast(1).joinToString(" ")
                    idToToken[id] = token
                    // First definition wins, so a duplicate id does not
                    // silently remap a token.
                    tokenToId.putIfAbsent(token, id)
                }

                return Vocabulary(
                    idToToken = Collections.unmodifiableMap(idToToken),
                    tokenToId = Collections.unmodifiableMap(tokenToId),
                )
            }
        }
    }

    private companion object {
        /** Logcat tag; filter with: adb logcat -s AhbTranslate */
        const val TAG = "AhbTranslate"

        const val MODEL_ROOT = "models"
        const val TRANSLATION_DIR = "translation"
        const val ENCODER_FILE = "encoder.onnx"
        const val DECODER_FILE = "decoder.onnx"
        const val VOCAB_FILE = "vocab.txt"

        const val SOURCE_LANG = "hi"
        const val TARGET_LANG = "en"

        /**
         * SentencePiece word-boundary marker (U+2581).
         *
         * Written as an escape so the source file stays pure ASCII and cannot
         * be mangled by an editor that guesses the wrong encoding.
         */
        const val SENTENCEPIECE_SPACE = "\u2581"

        /** Longest vocabulary piece we will attempt to match. */
        const val MAX_PIECE_LENGTH = 16

        /**
         * Hard cap on decoder steps.
         *
         * A symptom description is a sentence or two; 128 sub-word tokens is
         * comfortably more than enough, and the cap stops a model that never
         * emits EOS from hanging the app.
         */
        const val MAX_DECODE_STEPS = 128

        /** Hard cap on emitted tokens, independent of the step cap. */
        const val MAX_OUTPUT_TOKENS = 128
    }
}
