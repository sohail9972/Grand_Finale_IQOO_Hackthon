package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where the speech-recognition model files live, and whether they are present.
 *
 * ---------------------------------------------------------------------------
 * WHY THE MODELS ARE NOT IN THE APK
 * ---------------------------------------------------------------------------
 * A Hindi zipformer transducer is roughly 100-300 MB. Bundling it would push
 * the APK past 500 MB once Gemma and IndicTrans2 are added too, which makes the
 * app painful to install and impossible to iterate on during a 35-hour build.
 *
 * So the models are side-loaded once (adb push, or a file picker on first run)
 * into the app's private files directory. After that the app is fully offline
 * forever - there is no download at runtime, and no network permission at all.
 *
 * Layout under `filesDir/models/`:
 *
 *   asr/
 *     encoder.onnx
 *     decoder.onnx
 *     joiner.onnx
 *     tokens.txt
 *
 * The expected model is a sherpa-onnx **streaming zipformer transducer** for
 * Hindi, e.g. `sherpa-onnx-streaming-zipformer-hi-*`. Any zipformer transducer
 * with the same four files works; the filenames above are what we look for.
 */
object AsrModels {

    private const val TAG = "AhbAsr"

    /** Directory under `filesDir` that holds every on-device model. */
    const val MODEL_ROOT = "models"

    /** Sub-directory for the ASR model. */
    const val ASR_DIR = "asr"

    /** Encoder graph. */
    const val ENCODER = "encoder.onnx"

    /** Decoder graph. */
    const val DECODER = "decoder.onnx"

    /** Joiner graph. */
    const val JOINER = "joiner.onnx"

    /** Token table mapping model output ids to text. */
    const val TOKENS = "tokens.txt"

    /**
     * Sample rate the feature extractor must be fed at.
     *
     * 16 kHz mono is the universal ASR convention and what every sherpa-onnx
     * Hindi model expects. Feeding 44.1 kHz audio would produce gibberish.
     */
    const val SAMPLE_RATE = 16_000

    /** Feature dimension for the kaldi-style fbank front end. */
    const val FEATURE_DIM = 80

    /**
     * The directory containing the ASR model.
     *
     * @param context any context; the app's private files directory is used.
     */
    fun asrDir(context: Context): File =
        File(File(context.filesDir, MODEL_ROOT), ASR_DIR)

    /** Absolute path to the encoder graph. */
    fun encoderPath(context: Context): String =
        File(asrDir(context), ENCODER).absolutePath

    /** Absolute path to the decoder graph. */
    fun decoderPath(context: Context): String =
        File(asrDir(context), DECODER).absolutePath

    /** Absolute path to the joiner graph. */
    fun joinerPath(context: Context): String =
        File(asrDir(context), JOINER).absolutePath

    /** Absolute path to the token table. */
    fun tokensPath(context: Context): String =
        File(asrDir(context), TOKENS).absolutePath

    /**
     * True when every file the recogniser needs is on disk.
     *
     * Checked before constructing the recogniser, because sherpa-onnx throws a
     * native exception (not a Kotlin one) when a path is missing - and a native
     * abort cannot be caught, so it would take the whole app down.
     *
     * @param context any context.
     */
    fun isReady(context: Context): Boolean {
        val dir = asrDir(context)
        val required = listOf(ENCODER, DECODER, JOINER, TOKENS)
        val missing = required.filterNot { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

        if (missing.isNotEmpty()) {
            // Logged at debug, not error: a missing model is an expected state
            // on a fresh install and the UI already handles it.
            Log.d(TAG, "ASR model incomplete; missing=$missing in ${dir.absolutePath}")
            return false
        }
        return true
    }

    /**
     * User-facing explanation shown when voice input cannot be used.
     *
     * The absent file names go to the log, not the screen: the owner cannot act
     * on "encoder.onnx", and listing them reads as a fault rather than a mode.
     *
     * @return a short sentence describing the fallback, or null when ready.
     */
    fun describeMissing(context: Context): String? {
        val dir = asrDir(context)
        val required = listOf(ENCODER, DECODER, JOINER, TOKENS)
        val missing = required.filterNot { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
        if (missing.isEmpty()) return null
        Log.i(TAG, "ASR model incomplete at ${dir.absolutePath}; missing=$missing")
        return "Voice input is not set up on this phone yet. Type the symptoms " +
            "below - it works exactly the same."
    }
}