package com.sohailsquad.animalhealthbridge.ml.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Streaming, fully-offline speech recognition for Hindi.
 *
 * ---------------------------------------------------------------------------
 * DESIGN
 * ---------------------------------------------------------------------------
 * One background coroutine owns a strict loop: read a chunk of PCM from
 * [AudioRecord], hand it to the sherpa-onnx stream, decode whatever is ready,
 * and publish the running transcript. The UI never blocks - partial results
 * arrive on [partialTranscript] and finished sentences on [finalTranscript].
 *
 * ---------------------------------------------------------------------------
 * FALLBACK CONTRACT
 * ---------------------------------------------------------------------------
 * Speech recognition is the most failure-prone part of the app: the owner may
 * deny the microphone, the phone may have no mic, the model may not be
 * side-loaded, or another app may hold the mic. Each of those is reported as a
 * distinct [State] so the screen can always offer the "type instead" path. The
 * engine never throws to its caller and never crashes the app.
 *
 * @param context used for the model path; the application context is retained.
 */
class AsrEngine(context: Context) {

    private val appContext = context.applicationContext

    /** Everything the UI needs in order to render the recorder. */
    sealed interface State {
        /** Not recording, model not loaded. */
        data object Idle : State

        /** Loading the model. Can take a few seconds the first time. */
        data object Preparing : State

        /** Capturing and transcribing. */
        data object Listening : State

        /** Stopped cleanly; the final transcript is available. */
        data object Finished : State

        /**
         * Cannot record. [reason] is already user-facing text.
         *
         * @param canRetry whether tapping the mic again could plausibly work.
         */
        data class Unavailable(val reason: String, val canRetry: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Current recorder state, for rendering. */
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partialTranscript = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 32,
        // DROP_OLDEST: a fast talker must never stall the audio loop waiting on
        // the UI. Losing an intermediate partial is harmless - the next one
        // supersedes it.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Running transcript, updated as the owner speaks. */
    val partialTranscript: SharedFlow<String> = _partialTranscript.asSharedFlow()

    private val _finalTranscript = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emitted once per finished utterance. */
    val finalTranscript: SharedFlow<String> = _finalTranscript.asSharedFlow()

    /** The recogniser, or null before [prepare] succeeds. */
    @Volatile
    private var recognizer: OnlineRecognizer? = null

    /** Live decode stream; non-null only while listening. */
    @Volatile
    private var stream: OnlineStream? = null

    /** The capture loop, cancelled by [stop]. */
    private var captureJob: Job? = null

    /** Guards against overlapping start() calls from a double-tapped button. */
    @Volatile
    private var running = false

    /** Latest recognised text, so [stop] can emit a result even if quiet. */
    @Volatile
    private var latestText: String = ""

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Loads the recogniser if it is not already in memory.
     *
     * @return true when a recogniser is ready to use.
     */
    fun prepare(): Boolean {
        recognizer?.let { return true }

        // MUST run before touching sherpa-onnx: constructing OnlineRecognizer
        // with a missing file aborts natively, and a native abort cannot be
        // caught by try/catch - it would take the whole process down.
        if (!AsrModels.isReady(appContext)) {
            val reason = AsrModels.describeMissing(appContext)
                ?: "Voice input is not set up on this phone yet."
            Log.w(TAG, "prepare() failed: $reason")
            _state.value = State.Unavailable(reason = reason, canRetry = false)
            return false
        }

        return try {
            _state.value = State.Preparing
            recognizer = buildRecognizer()
            Log.i(TAG, "ASR model loaded")
            _state.value = State.Idle
            true
        } catch (t: Throwable) {
            // Throwable, not Exception: a malformed ONNX graph surfaces as an
            // Error from the native layer.
            Log.e(TAG, "Failed to load ASR model", t)
            recognizer = null
            _state.value = State.Unavailable(
                reason = "Could not load the speech model. Type the symptoms instead.",
                canRetry = false,
            )
            false
        }
    }

    /**
     * Builds the sherpa-onnx recogniser from the on-disk model.
     *
     * Configured as a streaming zipformer **transducer** (encoder + decoder +
     * joiner) with endpointing on, so a natural pause ends a sentence and the
     * transcript is flushed rather than held until the owner stops.
     *
     * `modelType` is deliberately left empty: sherpa-onnx then auto-detects the
     * architecture from the encoder graph's metadata, which keeps the engine
     * working if the side-loaded model is a different zipformer export.
     */
    private fun buildRecognizer(): OnlineRecognizer {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = AsrModels.encoderPath(appContext),
                decoder = AsrModels.decoderPath(appContext),
                joiner = AsrModels.joinerPath(appContext),
            ),
            tokens = AsrModels.tokensPath(appContext),
            // Two threads is the sweet spot on the mid-range Snapdragon this
            // targets: more threads contend with the UI for big cores and add
            // heat without improving latency.
            numThreads = 2,
            debug = false,
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AsrModels.SAMPLE_RATE,
                featureDim = AsrModels.FEATURE_DIM,
            ),
            modelConfig = modelConfig,
            // Greedy search: fastest, and for short symptom descriptions the
            // accuracy gap versus beam search is negligible.
            decodingMethod = "greedy_search",
            // Endpointing turns natural pauses into sentence boundaries.
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                // Rule 1: 2.4 s of silence ends a sentence, once something was said.
                rule1 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 2.4f,
                    minUtteranceLength = 0.0f,
                ),
                // Rule 2: 1.2 s of silence ends even a very short utterance.
                rule2 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 1.2f,
                    minUtteranceLength = 0.0f,
                ),
                // Rule 3 disabled: an upper bound would cut off a farmer who is
                // simply describing symptoms at length.
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.0f,
                    minUtteranceLength = 0.0f,
                ),
            ),
        )

        return OnlineRecognizer(assetManager = appContext.assets, config = config)
    }

    /**
     * Starts listening.
     *
     * Returns immediately; transcription arrives through the flows. On failure
     * [state] becomes [State.Unavailable] and the caller should surface the
     * typing fallback.
     */
    fun start() {
        if (running) {
            Log.d(TAG, "start() ignored: already running")
            return
        }
        if (!prepare()) return

        val rec = recognizer
        if (rec == null) {
            _state.value = State.Unavailable("Speech model unavailable.", canRetry = false)
            return
        }

        val record = try {
            createAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", t)
            null
        }

        if (record == null) {
            _state.value = State.Unavailable(
                reason = "Could not open the microphone. Check the permission and try again.",
                canRetry = true,
            )
            return
        }

        val liveStream = try {
            rec.createStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create ASR stream", t)
            safeRelease(record)
            _state.value = State.Unavailable(
                reason = "Could not start speech recognition. Type the symptoms instead.",
                canRetry = true,
            )
            return
        }

        stream = liveStream
        latestText = ""
        running = true
        _state.value = State.Listening

        captureJob = scope.launch { captureLoop(rec, liveStream, record) }
    }

    /**
     * Stops listening and emits whatever was recognised.
     *
     * Idempotent: calling it while already stopped does nothing.
     */
    fun stop() {
        if (!running) return
        running = false

        // Cancel first so the loop's finally block releases the mic promptly.
        captureJob?.cancel()
        captureJob = null

        val text = latestText.trim()
        scope.launch {
            if (text.isNotEmpty()) {
                _finalTranscript.tryEmit(text)
            }
            _state.value = State.Finished
            Log.i(TAG, "Stopped; final transcript length=${text.length}")
        }
    }

    /**
     * Releases the recogniser and stops the scope.
     *
     * Call from the owning screen's onDispose; afterwards the engine is unusable
     * and a new instance must be created.
     */
    fun release() {
        stop()
        try {
            stream?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release ASR stream", t)
        }
        stream = null

        try {
            recognizer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release ASR recogniser", t)
        }
        recognizer = null
    }

    /**
     * The capture/decode loop.
     *
     * Runs on [Dispatchers.Default] rather than IO: `AudioRecord.read` blocks,
     * and Default's larger pool keeps a blocked audio thread from starving the
     * database work sharing the IO pool.
     */
    private suspend fun captureLoop(
        rec: OnlineRecognizer,
        liveStream: OnlineStream,
        record: AudioRecord,
    ) {
        val bufferBytes = max(
            MIN_BUFFER_BYTES,
            AudioRecord.getMinBufferSize(
                AsrModels.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
        // ShortArray of half the byte count: PCM 16-bit is 2 bytes per sample.
        val buffer = ShortArray(bufferBytes / 2)

        try {
            record.startRecording()
            Log.i(TAG, "Capture started (buffer=${buffer.size} samples)")

            while (scope.isActive && running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // Negative values are error codes; treat any lack of
                    // progress as the end of capture rather than spinning.
                    if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read; stopping")
                        break
                    }
                    continue
                }

                // sherpa-onnx wants floats in [-1, 1]; PCM 16-bit is
                // [-32768, 32767], so divide by 32768.
                val samples = FloatArray(read)
                for (i in 0 until read) {
                    samples[i] = buffer[i] / 32768.0f
                }

                liveStream.acceptWaveform(samples, AsrModels.SAMPLE_RATE)

                // Decode while frames are ready. isReady() is false when the
                // model needs more audio, so this is a no-op on early chunks.
                while (rec.isReady(liveStream)) {
                    rec.decode(liveStream)
                }

                val text = rec.getResult(liveStream).text.trim()
                if (text.isNotEmpty() && text != latestText) {
                    latestText = text
                    _partialTranscript.tryEmit(text)
                }

                // An endpoint is a sentence boundary. Flush and reset so the
                // running text stays accurate instead of re-emitting everything
                // said so far.
                if (rec.isEndpoint(liveStream)) {
                    if (text.isNotEmpty()) {
                        latestText = text
                        _finalTranscript.tryEmit(text)
                    }
                    rec.reset(liveStream)
                    latestText = ""
                }
            }
        } catch (cancellation: CancellationException) {
            // Expected on stop(); rethrow so structured concurrency unwinds.
            throw cancellation
        } catch (t: Throwable) {
            Log.e(TAG, "Capture loop failed", t)
            _state.value = State.Unavailable(
                reason = "Recording stopped unexpectedly. You can type the symptoms instead.",
                canRetry = true,
            )
        } finally {
            // Flush anything still buffered, then tear the mic down. Runs on
            // cancellation too, which is what clears the mic indicator.
            try {
                liveStream.inputFinished()
            } catch (t: Throwable) {
                Log.w(TAG, "inputFinished failed", t)
            }
            safeRelease(record)
            running = false
            Log.i(TAG, "Capture stopped; microphone released")
        }
    }

    /**
     * Builds an [AudioRecord] for 16 kHz mono PCM.
     *
     * @return the recorder, or null when the device reports no usable mic.
     */
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            AsrModels.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            // A non-positive value means the hardware cannot do this format.
            Log.w(TAG, "getMinBufferSize returned $minBuffer; no usable microphone")
            return null
        }

        val record = AudioRecord(
            // VOICE_RECOGNITION, not MIC: this source disables the aggressive
            // AGC and noise suppression tuned for telephony, which distort the
            // spectrum the acoustic model was trained on.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AsrModels.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // 2x headroom: a single-buffer ring overflows whenever the decode
            // step outlasts the read, which silently drops audio.
            max(minBuffer * 2, MIN_BUFFER_BYTES),
        )

        return if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            Log.w(TAG, "AudioRecord failed to initialise (state=${record.state})")
            safeRelease(record)
            null
        }
    }

    /** Stops and releases a recorder, swallowing any platform failure. */
    private fun safeRelease(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.stop failed", t)
        }
        try {
            record.release()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.release failed", t)
        }
    }

    private companion object {
        /** Logcat tag; filter with: adb logcat -s AhbAsr */
        const val TAG = "AhbAsr"

        /**
         * Floor for the capture buffer, in bytes.
         *
         * 8 KB is 4096 samples at 16-bit, i.e. ~256 ms of audio - comfortably
         * above the 20-40 ms frames the model consumes.
         */
        const val MIN_BUFFER_BYTES = 8 * 1024
    }
}