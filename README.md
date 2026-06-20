# YouForge

**An offline, private, on-device creator toolkit for Android.**

YouForge is a single app with two tools behind a home screen:

1. **Thumbnail Maker** — turn a photo + a short description into a polished
   1280×720 YouTube thumbnail, with an on-device AI title, text effects,
   stickers, background removal and face framing.
2. **Video Normalizer** — an offline 5-band compressor + limiter that loudness-
   normalizes a video's audio (EBU R128 / LUFS), with a live preview and presets.

Everything runs **on the device**. Nothing is uploaded. The only network use is an
optional, one-time download of the on-device AI models — after that the app works
fully offline, even in airplane mode. The app declares a single permission:
`INTERNET` (for those model downloads).

- **Package:** `eu.cisodiagonal.youforge`
- **Version:** 1.0-r17 (versionCode 17)
- **Platform:** Android 10+ (API 29 → 35), 64-bit ARM (`arm64-v8a`)
- **Built with:** Kotlin 2.4 · Jetpack Compose · Material 3 · NDK (llama.cpp)
- **License:** [Apache-2.0](LICENSE)

For the step-by-step user guide, see **[MANUAL.md](MANUAL.md)**. For version
history and device notes, see **[CHANGELOG.md](CHANGELOG.md)**.

**Download:** prebuilt `arm64-v8a` release APKs are attached to each
[GitHub Release](https://github.com/diagonalciso/YouForge/releases) (latest:
**v1.0-r17**). Or build it yourself — see [Build](#build).

---

## Why offline?

Creator tools usually ship your photos, audio and ideas to a cloud API. YouForge
does the opposite: the language model, the speech-to-text, the image segmentation
and the face detector all run locally with MediaPipe and Vosk. Your media never
leaves the device, there's no account, no subscription, and no rate limit.

---

## Features

### Thumbnail Maker

**AI title (on-device LLM)**
- Describe the video in a sentence; a small instruct model writes a punchy title +
  style as JSON, which the renderer composites onto the photo.
- **Two on-device engines, one picker:**
  - **MediaPipe GenAI** — runs `.task` bundles (Qwen2.5, TinyLlama).
  - **llama.cpp** — runs `.gguf` models via a bundled native (NDK) backend, which
    opens the entire ungated GGUF ecosystem on Hugging Face. The GGUF path uses a
    **GBNF grammar** to force valid OverlaySpec JSON (uppercase title, locked
    style enums, `#RRGGBB` colours) and a **keep-warm** model cache so only the
    first suggestion per session pays the load cost.
- **Multiple models side-by-side** — download several (`.task` or `.gguf`), switch
  the active one instantly (no re-download). One-tap **"Download all"**, per-model
  download progress with **SHA-256 verification**, a custom model URL, or import a
  model file you already have (the format is auto-detected from its magic bytes).
- Works without any model too: a deterministic offline **template** generator
  picks a sensible title style from keywords.

**Title from video (on-device speech-to-text)**
- Pick a clip; YouForge transcribes its speech locally with **Vosk** and feeds the
  transcript to the title generator. The ~40 MB English speech model downloads once
  on first use, then runs offline.

**Layout & text**
- **Free transform** — tap the title or a sticker on the preview, drag to move,
  two-finger pinch to scale and rotate. Rotation sliders and reset too.
- **Text effects** — glow, neon, gradient, pop, bold outline, shadow, plain — with
  a live preview strip and colour swatches.
- **Auto-fit** title wrapping (up to 3 lines), manual line breaks, subtitle.
- **Stickers** — vector shapes (fish/fire/arrow/circle), a red *SUBSCRIBE* pill,
  and ~60 emoji grouped by theme.

**Make it click (on-device vision + helpers)**
- **Background removal** — cut the subject out (MediaPipe selfie segmenter) and drop
  it on a dark or blurred background. A high-contrast subject-on-dark is the single
  biggest thumbnail click-through lever.
- **Auto-frame face** — detect the largest face (BlazeFace) and recrop with it in
  the upper third.
- **Contrast check** — warns when the title colour is hard to read over the photo,
  with a one-tap "Fix" to white/black.
- **A/B variants** — export three different title styles of the same thumbnail in
  one tap, to test which gets more clicks.
- **Style presets** + **brand kit** — five one-tap looks, plus save your own style
  and have it applied automatically to future thumbnails.

**Export** — 1280×720 PNG to `Pictures/YouForge`.

### Video Normalizer

- Offline **5-band** dynamics: per-band compressor + a brick-wall limiter.
- **Loudness normalization** to a target LUFS (EBU R128) with a true-peak ceiling.
- **Live preview** of the processed audio, and **presets**.
- Decodes/encodes with `MediaCodec` and remuxes with `MediaMuxer` — the video track
  is copied through untouched; only the audio is reprocessed.

---

## On-device models

| Purpose | Engine | Model | Size | When |
|---|---|---|---|---|
| Title suggestion (`.task`) | MediaPipe GenAI | Qwen2.5-1.5B-Instruct (default), Qwen2.5-0.5B-Instruct, TinyLlama-1.1B-Chat (`.task`, q8) | ~0.55–1.6 GB | Downloaded on demand from the in-app picker |
| Title suggestion (`.gguf`) | llama.cpp (NDK) | Qwen2.5-1.5B-Instruct, Qwen2.5-0.5B-Instruct (`.gguf`, q4_k_m) | ~0.47–1.1 GB | Downloaded on demand; any ungated HF GGUF via custom URL |
| Speech-to-text | Vosk | `vosk-model-small-en-us-0.15` | ~40 MB | Downloaded on first "Title from video" |
| Background removal | MediaPipe Vision | `selfie_segmenter.tflite` | ~250 KB | **Bundled in the APK** |
| Face framing | MediaPipe Vision | `blaze_face_short_range.tflite` | ~230 KB | **Bundled in the APK** |

The two small vision models ship inside the app, so cut-out and face-framing work
offline out of the box. The LLM and speech models are downloaded into the app's
private storage (never to shared storage, never to this repo) and reused offline
afterwards.

**All five suggested LLMs are ungated** (three `.task`, two `.gguf`) and download
with no Hugging Face login. Each download is checked against a known **SHA-256**
digest and rejected on mismatch. Gated models such as **Gemma 3** and
**Llama 3.2** (which require accepting a license) are intentionally left off the
list — you can still use them by pasting their `.task`/`.gguf` URL in the **custom
URL** field, or by importing a file you downloaded on a logged-in machine. The
model only writes the title text; the renderer does the visual work, so a 0.5–1.5B
instruct model is plenty.

---

## Compatibility

### Android versions

| Android | API | Status |
|---|---|---|
| 10 (Q) | 29 | ✅ Minimum supported (`minSdk 29`) |
| 11 (R) | 30 | ✅ Supported |
| 12 / 12L | 31–32 | ✅ Supported |
| 13 (Tiramisu) | 33 | ✅ Supported · confirmed device |
| 14 (Upside Down Cake) | 34 | ✅ Supported |
| 15 (Vanilla Ice Cream) | 35 | ✅ Built & targeted (`compileSdk`/`targetSdk 35`) |
| 9 (Pie) and older | ≤28 | ❌ Not supported (below `minSdk`) |

Android 10 (API 29) is the floor because of the photo/video document picker (SAF)
and scoped-storage export paths the app relies on. MediaPipe GenAI itself runs on
API 24+, but the rest of the app assumes 29+.

### CPU architecture

| ABI | Release APK | Debug APK | Notes |
|---|---|---|---|
| `arm64-v8a` (64-bit ARM) | ✅ | ✅ | The only target for releases — virtually all phones/tablets from ~2017 on |
| `x86_64` | ❌ | ✅ | Debug builds add it for the Android emulator. **Background removal and Auto-frame face do not work on x86_64** — MediaPipe Vision ships no x86_64 native library. The LLM and Vosk paths do work on x86_64. |
| `armeabi-v7a` (32-bit ARM) | ❌ | ❌ | Not built. The MediaPipe GenAI runtime is 64-bit only. |
| `x86` (32-bit) | ❌ | ❌ | Not built. |

> **Use a 64-bit ARM device for the full feature set.** The x86_64 debug variant
> exists only so the app can be exercised on an emulator, where the two camera-AI
> features are unavailable by design.

### Memory & performance guidance

The on-device LLM is the only heavy feature. Everything else (rendering, export,
contrast, presets, the 5-band audio engine, background removal, face framing) is
light and runs comfortably on any supported device.

| Device RAM | LLM suggestion |
|---|---|
| < 4 GB | Use the **offline template** generator (no LLM). LLM may fail to load. |
| 4–6 GB | Qwen2.5-**0.5B** (≈0.55 GB) — fast, low memory. |
| 6–8 GB | Qwen2.5-**1.5B** (default) or TinyLlama-1.1B. |
| 8 GB+ | Any of the above; larger custom models possible. |

LLM speed scales with the SoC. On a flagship-class chip (e.g. Snapdragon 865+ /
recent Dimensity / Tensor) a title generates in a few seconds; on older or
mid-range chips it takes longer but still works. There is no minimum — slow just
means slow, and the template path is always instant.

### Tested

- **Samsung Galaxy Tab S7+** (Snapdragon 865+, 8 GB, One UI on Android up to 13) —
  primary test device, all features confirmed.
- **Android emulator** (API 29, x86_64) — used for CI-style verification of
  rendering, export, LLM title generation and Vosk speech-to-text. (Vision features
  are arm-only and verified on hardware instead.)

Any 64-bit ARM phone or tablet on Android 10+ with ≥4 GB RAM should run the full
app; ≥6 GB is recommended if you want to use the on-device LLM.

---

## Build

Requirements: JDK 17, the Android SDK, and a `local.properties` with `sdk.dir`
pointing at it (or an `ANDROID_HOME` env var). The Gradle wrapper pins Gradle
9.6.0, so no global Gradle install is needed. The GGUF backend is built with the
NDK, so the SDK must have **NDK `26.3.11579264`** and **CMake `3.22.1`** installed:

```bash
sdkmanager "ndk;26.3.11579264" "cmake;3.22.1"
```

The native `llama.cpp` source is **not vendored** in this repo (it is
`.gitignore`d). Fetch it once before the first build — the script clones a pinned
llama.cpp commit and strips its `.git`:

```bash
bash app/src/main/cpp/fetch-llama.sh
```

```bash
# Debug APK (also runnable on an x86_64 emulator)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Signed release (provide your own keystore via these properties or env vars)
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/path/to/keystore.jks \
  -PRELEASE_STORE_PASSWORD=... \
  -PRELEASE_KEY_ALIAS=... \
  -PRELEASE_KEY_PASSWORD=...
```

The release build is unsigned unless those properties (or the matching `RELEASE_*`
environment variables) are supplied. **No keystore or password is stored in the
repo.**

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage

A full walkthrough — every button, both tools, model management and
troubleshooting — is in **[MANUAL.md](MANUAL.md)**. The short version:

1. **Thumbnail Maker** → **Pick photo** → *(optional)* cut out / auto-frame →
   **Describe it** → **Suggest (AI)** / **Template** / **🎙 Title from video** →
   tweak text, effect, stickers, position → **Export 1280×720 PNG**.
2. **Video Normalizer** → pick a video → choose a preset → preview → process.

---

## Project structure

```
app/src/main/java/eu/cisodiagonal/youforge/
├─ MainActivity.kt            # launcher + home screen + nav
├─ thumb/                     # Thumbnail Maker
│  ├─ ThumbScreen.kt          # Compose UI for the whole tool
│  ├─ ThumbnailRenderer.kt    # deterministic 1280×720 compositor
│  ├─ OverlaySpec.kt          # title/style data model (+ TextEffect/Position enums)
│  ├─ Sticker.kt              # sticker model + palette
│  ├─ Contrast.kt             # WCAG contrast math (pure Kotlin)
│  ├─ Variants.kt             # A/B style variants (pure Kotlin)
│  ├─ Presets.kt              # named style presets (pure Kotlin)
│  ├─ TemplateProvider.kt     # offline title generator
│  ├─ AiProvider.kt / OnDeviceLlm.kt  # on-device LLM (MediaPipe GenAI, .task)
│  ├─ LlamaBridge.kt / LlamaCppEngine.kt  # on-device LLM (llama.cpp, .gguf)
│  ├─ ModelManager.kt         # multi-model store (.task + .gguf), SHA-256, downloads
│  ├─ Settings.kt             # prefs, suggested models (ModelFormat), brand kit
│  └─ VisionTools.kt          # background removal + face detect (MediaPipe Vision)
├─ cpp/                       # native GGUF backend (NDK / CMake)
│  ├─ CMakeLists.txt          # builds llama.cpp + the JNI lib
│  ├─ llama-android.cpp       # JNI: keep-warm load + GBNF-constrained decode
│  └─ fetch-llama.sh          # clones the pinned llama.cpp source (gitignored)
├─ asr/                       # on-device speech-to-text
│  ├─ VoskModelManager.kt     # download + unpack the speech model
│  ├─ AudioPcmDecoder.kt      # video → 16 kHz mono PCM (MediaCodec)
│  └─ AudioTranscriber.kt     # Vosk recognizer → transcript
└─ video/                     # Video Normalizer (5-band comp/limiter, LUFS)
   ├─ VideoScreen.kt  Dsp.kt  BandProcessor.kt  Loudness.kt
   ├─ MediaProcessor.kt  Presets.kt  PreviewPlayer.kt
app/src/main/assets/          # bundled vision models (*.tflite)
```

A deliberate design choice: the non-AI logic (contrast, variants, presets, the
overlay/title math, the DSP) is written in **plain Kotlin with no Android imports**,
so it can be reused directly by the planned desktop builds.

---

## Tech stack

- **Kotlin 2.4**, **Jetpack Compose** (compose compiler plugin), **Material 3**
- **MediaPipe Tasks** — GenAI (`.task` LLM), Vision (image segmenter + face detector)
- **llama.cpp** via the **NDK** (CMake) — `.gguf` LLM with a GBNF JSON grammar
- **Vosk** (`vosk-android`) — offline speech recognition
- Android **MediaCodec / MediaExtractor / MediaMuxer** for audio/video
- Gradle **9.6.0**, `minSdk 29`, `compileSdk 35`, Java 17, `arm64-v8a`

---

## Roadmap

- Desktop builds (**Linux / macOS / Windows**) reusing the pure-Kotlin core.
- Additional speech-model languages.
- Optional generative backgrounds (on-device diffusion) — heavier, gated behind a
  capable-device check.

---

## Privacy

YouForge makes no analytics calls and has no backend. The only outbound network
requests are HTTPS downloads of the on-device model files (from Hugging Face and the
Vosk model host) when you choose to install them. Your photos, videos and titles are
processed entirely on the device and never leave it.

Cleartext (plain-HTTP) traffic is **disabled** at the platform level
(`usesCleartextTraffic=false` + a network security config), model downloads are
**SHA-256 verified**, archive extraction is guarded against Zip-Slip, and release
builds are **R8-minified/shrunk**. `allowBackup` is off so model files and prefs are
not swept into cloud backups.

---

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE).

Third-party components keep their own licenses:

- **MediaPipe** (Apache-2.0) and the bundled `selfie_segmenter` /
  `blaze_face_short_range` models (Google, permissive model-card terms).
- **Vosk** (`vosk-android`, Apache-2.0) and `vosk-model-small-en-us-0.15`
  (Apache-2.0).
- The LLM `.task` models you download keep the license of their respective
  repositories (e.g. Qwen — Apache-2.0; TinyLlama — Apache-2.0). YouForge does not
  redistribute them; it downloads them from their original hosts on request.
