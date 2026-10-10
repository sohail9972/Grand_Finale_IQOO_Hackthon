# Offline model setup

The application does not download models or request INTERNET/ACCESS_NETWORK_STATE. Typed intake, local drafts, triage, review, questions, prescriptions, and records do not require model files.

## Speech

Place `model.int8.onnx` and `tokens.txt` under the installed application's external-files directory in `models/indicconformer/<language>/`. For Kannada in the debug package this is `/sdcard/Android/data/com.sohailsquad.animalhealthbridge.debug/files/models/indicconformer/kn/`. Configurable language codes are `kn`, `ta`, `te`, `ml`, `mr`, `bn`, `gu`, and `pa`; choose the corresponding input language in the app. Selecting a language does not install its model.

The recognizer uses `OfflineNemoEncDecCtcModelConfig` with a **sherpa-compatible NeMo CTC export**, not an arbitrary AI4Bharat ONNX graph. Preflight checks require ONNX metadata `model_type=nemo_ctc`; when present, `language` must match the selected folder and `normalize_type` must be `per_feature`. Absent language metadata no longer defaults to Hindi. Do not modify `export-meta.json`, the model's normalization metadata, or the shared multilingual `tokens.txt`; its Bengali-first vocabulary is intentional. Recognition uses bounded 30-second recordings with periodic offline prefix decoding, not a true streaming model.

Both Speak buttons try IndicConformer first. Missing files, load/capture failures, or empty recognition results fall back to `AndroidAsr` with the same selected language. That fallback requires Android 12+ and an installed on-device speech service/language pack; there is no cloud recognizer fallback. If neither backend succeeds, typing remains available. Use logcat tag `AhbAsr` to inspect resolved paths, metadata, and fallback reasons. Recognition quality and the supplied Kannada bundle still require on-device verification.

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
