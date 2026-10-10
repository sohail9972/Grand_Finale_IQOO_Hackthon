# Offline model setup

The application does not download models or request INTERNET/ACCESS_NETWORK_STATE. Typed intake, local drafts, triage, review, questions, prescriptions, and records do not require model files.

## Speech

Place the FP32 `model.onnx` and `vocab.json` under the installed application's **internal** `filesDir` in `models/indicconformer-fp32/<language>/`. For Kannada in the debug package this is `/data/user/0/com.sohailsquad.animalhealthbridge.debug/files/models/indicconformer-fp32/kn/`. A repository copy is not automatically installed on the phone. Configurable language codes are `kn`, `ta`, `te`, `ml`, `mr`, `bn`, `gu`, and `pa`; choose the corresponding input language in the app. Selecting a language does not install its model.

FP32 is preferred and runs directly through ONNX Runtime on the CPU, using float32 `audio_signal [1,80,time]`, int64 `length [1]`, and float32 `logprobs [1,time,classes]`. Empty metadata is accepted. The frontend uses 16 kHz PCM, preemphasis 0.97, reflect-centered FFT512, symmetric Hann400, hop160, 80 Slaney-normalized mel bands, log guard 2^-24, and per-feature sample-standard-deviation normalization with epsilon 1e-5. Recognition happens after a bounded 30-second recording stops. The supplied 5,632-entry vocabulary has a 5,633-class output, so greedy CTC treats the appended class (5,632) as blank and never emits `<unk>`. Equal-width exports use blank index zero; other widths are rejected.

If FP32 is absent or cannot load, the existing sherpa INT8 path remains available. Place `model.int8.onnx` and `tokens.txt` in internal `filesDir/models/indicconformer/<language>/`, not external storage. This path uses `OfflineNemoEncDecCtcModelConfig` and requires a **sherpa-compatible NeMo CTC export**, not an arbitrary AI4Bharat graph. Preflight requires ONNX metadata `model_type=nemo_ctc`; optional `language` must match the selected folder and `normalize_type` must be `per_feature`. Do not modify `export-meta.json`, normalization metadata, or the shared multilingual vocabulary; Bengali-first tokens are intentional. INT8 retains periodic offline prefix decoding, but exports using unsupported `ConvInteger` operations will fail and need FP32 instead.

Both Speak buttons already call `AsrEngine`, which now prefers FP32 over INT8. Missing files, load/capture/inference failures, or empty recognition results return to the screen's existing `AndroidAsr` fallback with the same selected language. That fallback requires Android 12+ and an installed on-device speech service/language pack; there is no cloud recognizer fallback. If neither backend succeeds, typing remains available. Use logcat tag `AhbAsr` to inspect resolved paths, preprocessing, inference timing, and fallback reasons. The FP32 bundle has not been pushed or tested on the phone as part of this change; loading, latency, and recognition quality require on-device verification.

## Translation

Place `encoder.onnx`, `decoder.onnx`, `tokenizer.json`, and `config.json` in `models/indictrans2/<source>-<target>/` under external-files storage. Both directions need their own compatible exports; selecting a language does not install a model.

Supported encoder inputs are `input_ids` and `attention_mask`. The no-past decoder accepts `input_ids`, `encoder_hidden_states`, and optionally `encoder_attention_mask`, with float logits as its first output. Configuration must provide `eos_token_id` and `decoder_start_token_id`.

The tokenizer must contain a Unigram vocabulary with scores, explicit IndicTrans language tokens, Metaspace preprocessing, and NFC/NFKC normalization. Binary SentencePiece models, precompiled normalizers, byte-fallback tokenizers, and other decoder interfaces are not supported by this wrapper. Unsupported or absent exports preserve the original text, show a translation warning, and force low-confidence triage rather than inventing a translation. Actual IndicTrans2 quality and export compatibility still require validation with the intended model bundle.

## LLM and clinical boundaries

The existing GenieX 0.8.0 qairt/SM8850 loader and CaseSchema remain unchanged. Its external-files model directory remains `geniex/qwen3_1_7b`. Failed loading uses the existing keyword fallback, not a CPU model download.

Triage rules are the supplied demonstration rules, not clinically validated decision support. Missing rules escalate conservatively. Local vet/owner views are workflow modes, not authenticated professional roles; registration numbers are user-entered. A prescription requires a persisted approval but is not digitally signed. Follow-up dates are recorded; notification scheduling is not included in these steps.

PDF sharing is explicitly user initiated. Android backup rules remain unchanged, so absence of application network permissions is not a guarantee against OS-managed cloud backup. Media stays in app-private files and is not embedded in exported record PDFs.

## Device test safety

Gradle's connected-test cleanup uninstalled the debug app during this implementation run, deleting its previous records, preferences, and external-files models. The app was reinstalled; previous records and preferences could not be recovered. Model files can be restored from a separate surviving backup, but this does not restore patient records.

`gradle.properties` now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, the option supported by this project's Android Gradle plugin. Back up important device data before instrumentation tests; this option does not protect against manual uninstalls, incompatible-install handling, or data-clearing test tools. Direct `adb shell am instrument` avoids Gradle's package cleanup.

When `/data/local/tmp/geniex/qwen3_1_7b` survives, the opt-in `RestoreLocalModelDeviceTest` creates app-owned destination files and fills them using test shell commands. It skips ordinary test runs, reuses complete readable files, and refuses to overwrite incomplete existing files. Run it only with `-e restoreLocalModel true` and its explicit test class after installing the test APK. A shell-created external-files directory may not be readable by the app; validate recovery with `LlmEngineDeviceTest`, not just `adb shell ls`.
