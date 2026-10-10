package com.sohailsquad.animalhealthbridge.ml.translation

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import com.sohailsquad.animalhealthbridge.data.db.AppDatabase
import com.sohailsquad.animalhealthbridge.data.db.entity.TranslationCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

class Translator(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    data class TranslationResult(val text: String, val isFallback: Boolean, val source: String)
    suspend fun translate(text: String, sourceLang: String): String = translateResult(text, sourceLang).text
    suspend fun translateHindiToEnglish(text: String, cache: TranslationCacheDao? = null): TranslationResult =
        translateResult(text, "hi")

    suspend fun translateResult(text: String, sourceLang: String, targetLang: String = "en"): TranslationResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank() || sourceLang == targetLang) return@withContext TranslationResult(text, false, "identity")
            try {
                lock.withLock {
                    require(sourceLang in TranslatorConfig.languageTags && targetLang in TranslatorConfig.languageTags) {
                        "Unsupported translation language pair."
                    }
                    val dao = AppDatabase.get(context).translationDao()
                    val hash = MessageDigest.getInstance("SHA-256").digest(
                        "$sourceLang\u0000$targetLang\u0000$text".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    try {
                        dao.getByHash(hash)?.takeIf { it.sourceText == text && it.sourceLang == sourceLang }?.let {
                            Log.i("AhbTranslate", "Cache hit $sourceLang->$targetLang")
                            return@withLock TranslationResult(it.translatedText, false, "cache")
                        }
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        Log.w("AhbTranslate", "Cache read failed", failure)
                    }
                    val directory = TranslatorConfig.directory(context, sourceLang, targetLang)
                    val required = listOf("encoder.onnx", "decoder.onnx", "tokenizer.json", "config.json")
                    require(required.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }) {
                        "Model files missing at $directory"
                    }
                    val translated = decode(directory, text, sourceLang, targetLang)
                    require(translated.isNotBlank())
                    try { dao.insert(TranslationCache(hash, text, sourceLang, translated)) }
                    catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        Log.w("AhbTranslate", "Cache write failed", failure)
                    }
                    Log.i("AhbTranslate", "Translated $sourceLang->$targetLang")
                    TranslationResult(translated, false, "model")
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) {
                Log.w("AhbTranslate", "Translation unavailable $sourceLang->$targetLang; preserving original", failure)
                TranslationResult(text, true, "unavailable")
            }
        }

    private fun decode(directory: File, text: String, source: String, target: String): String {
        val tokenizer = UnigramTokenizer(JSONObject(File(directory, "tokenizer.json").readText()))
        val config = JSONObject(File(directory, "config.json").readText())
        val sourceTag = requireNotNull(TranslatorConfig.languageTags[source])
        val targetTag = requireNotNull(TranslatorConfig.languageTags[target])
        val input = longArrayOf(tokenizer.languageId(sourceTag), tokenizer.languageId(targetTag)) +
            tokenizer.encode(text) + config.getLong("eos_token_id")
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(File(directory, "encoder.onnx").path, options).use { encoder ->
                env.createSession(File(directory, "decoder.onnx").path, options).use { decoder ->
                    require(encoder.inputNames == setOf("input_ids", "attention_mask"))
                    require(decoder.inputNames.all { it in setOf("input_ids", "encoder_hidden_states", "encoder_attention_mask") })
                    require("encoder_hidden_states" in decoder.inputNames)
                    ids(env, input).use { sourceIds ->
                        ids(env, LongArray(input.size) { 1 }).use { mask ->
                            encoder.run(mapOf("input_ids" to sourceIds, "attention_mask" to mask)).use { encoded ->
                                val hidden = encoded.get(0) as OnnxTensor
                                val output = mutableListOf(config.getLong("decoder_start_token_id"))
                                var finished = false
                                repeat(256) {
                                    if (!finished) {
                                        ids(env, output.toLongArray()).use { prefix ->
                                            val feeds = mutableMapOf("input_ids" to prefix, "encoder_hidden_states" to hidden)
                                            if ("encoder_attention_mask" in decoder.inputNames) feeds["encoder_attention_mask"] = mask
                                            decoder.run(feeds).use { decoded ->
                                                val logits = decoded.get(0) as OnnxTensor
                                                val width = logits.info.shape.last().toInt()
                                                val buffer = logits.floatBuffer
                                                val offset = buffer.remaining() - width
                                                val best = (0 until width).maxBy { buffer.get(offset + it) }.toLong()
                                                if (best == config.getLong("eos_token_id")) finished = true else output += best
                                            }
                                        }
                                    }
                                }
                                require(finished) { "Decoder did not finish." }
                                return tokenizer.decode(output.drop(1))
                            }
                        }
                    }
                }
            }
        }
    }
    private fun ids(env: OrtEnvironment, ids: LongArray) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
    fun close() = Unit
}
