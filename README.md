# Animal Health Bridge

> Offline-first, multilingual on-device AI that turns animal owners' spoken 
> symptoms into structured veterinary cases.

**iQOO City Battles 2026 · HealthTech Track · Team Sohail Squad**

---

## The Problem

Animal owners — smallholder dairy farmers and pet parents — cannot reliably 
judge urgency, capture useful evidence, or explain symptoms across language 
barriers. Veterinarians receive vague calls, blurry photos, and no history. 
Every wasted consultation costs the animal time.

**Existing tools solve pieces in isolation.** No single offline loop connects 
identity → evidence → triage → vet-approved treatment → continuous record.

---

## Our Solution

An Android app that runs entirely on the phone. The owner speaks in their own 
language; the app turns those words into a structured veterinary case the vet 
can act on.

### Core Loop

```
Animal ID (NFC/QR) → Voice Intake → On-device LLM structuring 
→ Rules triage → Vet review → Evidence requests → Prescription → Health record
```

### What the App Does NOT Do

- Does not diagnose
- Does not prescribe
- Does not replace in-person examination
- Vet is the sole prescriber

---

## On-Device AI Stack

| Component | Technology | Runtime | Status |
|---|---|---|---|
| **Speech Recognition** | AI4Bharat IndicConformer FP32 (9 languages) | ONNX Runtime | ✅ Working |
| **Speech Recognition (English)** | Moonshine Tiny English INT8 | sherpa-onnx | ✅ Working |
| **LLM (Case Structuring)** | Qwen3-1.7B | Snapdragon NPU via Qualcomm GenieX (QAIRT) | ✅ Working |
| **LLM (Behaviour Analysis)** | Qwen3-1.7B | Snapdragon NPU via Qualcomm GenieX (QAIRT) | ✅ Working |
| **Triage** | Deterministic rules engine | Pure Kotlin (no AI) | ✅ Working |
| **Image Validation** | MobileNetV3 | ONNX Runtime | ⏳ Phase 2 |
| **Video Behaviour Analysis** | SmolVLM2-500M | LiteRT-LM | ⏳ Phase 2 (validated in Gallery) |

### Verified Performance (measured on iQOO 15, SM8850)

| Metric | Value |
|---|---|
| LLM decode speed | 28.8 tok/s on Hexagon NPU |
| LLM time to first token | 70 ms |
| Case structuring latency | ~2 seconds |
| ASR transcription latency | 300–350 ms |
| Offline voice languages | 10 (9 IndicConformer + English Moonshine) |

---

## Supported Languages

**Voice input (10 languages, all on-device):**

| Language | Code | Model |
|---|---|---|
| Kannada | kn | IndicConformer FP32 |
| Hindi | hi | IndicConformer FP32 |
| Tamil | ta | IndicConformer FP32 |
| Telugu | te | IndicConformer FP32 |
| Malayalam | ml | IndicConformer FP32 |
| Marathi | mr | IndicConformer FP32 |
| Bengali | bn | IndicConformer FP32 |
| Gujarati | gu | IndicConformer FP32 |
| Punjabi | pa | IndicConformer FP32 |
| English | en | Moonshine Tiny INT8 |

**UI and typed input:** 13 languages — the 10 above plus Odia, Assamese, Urdu.

---

## Offline Guarantee

The app has **no INTERNET permission**. This is enforced at the OS level and 
verifiable:

```bash
adb shell dumpsys package com.sohailsquad.animalhealthbridge.debug | grep -i internet
# Expected: empty output
```

Models are distributed via `scripts/download_models.ps1`, not committed to Git. 
The repo is under 40 MB.

---

## Repository Structure

```
app/
├── src/main/java/com/sohailsquad/animalhealthbridge/
│   ├── data/              Room database, DAOs, entities, repositories
│   ├── ml/
│   │   ├── asr/           IndicConformer + Moonshine ASR engines
│   │   ├── llm/           Qwen3-1.7B via GenieX
│   │   └── translation/   (Phase 2)
│   ├── triage/            Deterministic rules engine
│   ├── nfc/               NFC and QR scanning
│   ├── camera/            CameraX capture
│   ├── ui/screens/        All 8 Compose screens
│   ├── ui/components/     Shared composables
│   └── ui/theme/          Design system
├── libs/
│   └── sherpa-onnx-static-link-onnxruntime-1.13.8.aar
└── build.gradle.kts

scripts/
└── download_models.ps1

models/                    (gitignored — download separately)
```

---

## Screens

| # | Screen | Purpose |
|---|---|---|
| 1 | Language Select | 13 languages |
| 2 | Animal ID | NFC / QR / manual entry |
| 3 | Voice Intake | On-device ASR in the owner's language |
| 4 | Case Review | Structured case + triage badge |
| 5 | Vet Approval | Vet reviews, prescribes |
| 5b | Prescription | Generated after vet approval |
| 6 | Vet Query | Vet requests evidence (PHOTO/VIDEO/YES_NO/TEXT) |
| 7 | Health Record | Longitudinal timeline |

---

## Safety

| Principle | Implementation |
|---|---|
| No diagnosis | LLM extracts fields; never interprets clinically |
| No prescribing | Vet approves all treatment |
| Conservative triage | Rules-based, escalates when unsure |
| Primary metric | Under-triage rate |
| Privacy | Data stays on-device until owner shares |
| Mortality handling | "died" / "dead" / "not breathing" → EMERGENCY_NOW |

---

## Evidence Base

The problem statement is grounded in three published studies:

1. **Kumar et al. (2021), *Antibiotics* 10(9):1124** — Only 2% of 491 small 
   dairy farms in Assam & Haryana had heard of "withdrawal period." 
   DOI: `10.3390/antibiotics10091124`

2. **Beyene et al. (2018), *Frontiers in Veterinary Science* 5:2** — Paper-based 
   cattle reporting in Ethiopia reached only ~2/3 of cases to the federal service 
   by month-end. DOI: `10.3389/fvets.2018.00002`

3. **Farrow et al. (2026), *PLOS ONE* 21(1):e0339723** — 1,772 UK dog owners 
   underestimated urgency in 28.4% of vignette responses. 
   DOI: `10.1371/journal.pone.0339723`

---

## Setup

### 1. Clone the repo

```bash
git clone https://github.com/sohail9972/Grand_Finale_IQOO_Hackthon.git
cd Grand_Finale_IQOO_Hackthon
```

### 2. Download models

```powershell
./scripts/download_models.ps1
```

### 3. Push models to device

```powershell
$pkg = "com.sohailsquad.animalhealthbridge.debug"
adb shell "run-as $pkg mkdir -p files/models"
adb push models/. /data/local/tmp/models/
adb shell "run-as $pkg sh -c 'cp -r /data/local/tmp/models/. files/models/'"
```

### 4. Build and run

```powershell
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Roadmap

### Phase 1 (complete)
- [x] On-device LLM (Qwen3-1.7B on NPU)
- [x] 9-language IndicConformer ASR + English Moonshine
- [x] Rules-based triage (3 levels)
- [x] Behaviour analysis (text)
- [x] NFC / QR animal ID
- [x] Vet query loop (PHOTO / VIDEO / YES_NO / TEXT)
- [x] Prescription screen
- [x] Longitudinal health record
- [x] Offline by construction

### Phase 2 (documented)
- [ ] Video behaviour analysis (SmolVLM2-500M — validated on SM8850 GPU)
- [ ] Image validation (MobileNetV3 — reject non-animal images)
- [ ] Odia, Assamese, Urdu voice input
- [ ] SMS fallback for low connectivity
- [ ] Push delivery of vet questions

---

## Team

**Sohail Squad** — iQOO City Battles 2026

---

## License

MIT License. See [LICENSE](LICENSE).

Model licenses:
- **IndicConformer / IndicTrans2** — MIT (AI4Bharat)
- **Qwen3-1.7B** — Apache 2.0 (Alibaba / Qualcomm AI Hub)
- **Moonshine Tiny English** — MIT (Useful Sensors)
- **sherpa-onnx** — Apache 2.0

---

## Acknowledgments

- **AI4Bharat** for IndicConformer and IndicTrans2
- **Google AI Edge** for MediaPipe and LiteRT-LM
- **Qualcomm AI Hub** for the Qwen3-1.7B GENIEX_QAIRT model
- **k2-fsa** for sherpa-onnx
- **iQOO** and **Reskilll** for the City Battles platform

---

## Contact

GitHub: [@sohail9972](https://github.com/sohail9972)

---

**"Not replacing vets — giving them better information."**