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
- **Platform:** Android (minSdk 29 / Android 10+), arm64-v8a
- **Built with:** Kotlin · Jetpack Compose · Material 3
- **License:** see [License](#license)

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
- Describe the video in a sentence; a small instruct model (Qwen / Gemma / Llama,
  MediaPipe `.task`) writes a punchy title + style as JSON, which the renderer
  composites onto the photo.
- **Multiple models side-by-side** — download several, switch the active one
  instantly (no re-download). One-tap **"Download all"**, per-model download
  progress, custom `.task` URL, or import a `.task` you already have.
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
| Title suggestion | MediaPipe GenAI | Qwen2.5-1.5B/3B, Gemma3-1B, Llama-3.2-1B/3B (`.task`) | ~1–3.4 GB | Downloaded on demand from the in-app picker |
| Speech-to-text | Vosk | `vosk-model-small-en-us-0.15` | ~40 MB | Downloaded on first "Title from video" |
| Background removal | MediaPipe Vision | `selfie_segmenter.tflite` | ~250 KB | **Bundled in the APK** |
| Face framing | MediaPipe Vision | `blaze_face_short_range.tflite` | ~230 KB | **Bundled in the APK** |

The two small vision models ship inside the app, so cut-out and face-framing work
offline out of the box. The LLM and speech models are downloaded into the app's
private storage (never to shared storage, never to this repo) and reused offline
afterwards. All listed models are ungated and require no login.

---

## Build

Requirements: JDK 17, the Android SDK, and a `local.properties` with `sdk.dir`
pointing at it (or an `ANDROID_HOME` env var). The Gradle wrapper pins Gradle 8.9,
so no global Gradle install is needed.

```bash
# Debug APK
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
environment variables) are supplied. No keystore or password is stored in the repo.

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The app targets **arm64-v8a only** to keep the APK small. Use a 64-bit ARM device
> or emulator image.

---

## Usage manual

### Make a thumbnail
1. **Open** the app → **Thumbnail Maker**.
2. **Pick photo** — choose any image; HEIC and very large sensor photos are handled.
3. *(Optional)* **Photo (on-device AI)** row:
   - **Cut out · dark / · blur** — replace the background.
   - **Auto-frame face** — recrop around the subject.
   - **Restore photo** — undo the above.
4. **Describe it** in a sentence, then:
   - **Suggest (AI)** — uses the active on-device model (download one first via the
     **Model** button, top-right), or
   - **Template** — instant offline styling, or
   - **🎙 Title from video** — pick a clip and let the app transcribe the speech.
5. **Tweak**: edit the title text, drag/rotate it on the preview, pick an **effect**,
   colours and **position**, add **stickers**, apply a **preset** or your **brand
   kit**. Watch the **contrast** hint and tap **Fix** if it's low.
6. **Export 1280×720 PNG**, or **Export 3 A/B variants** to test styles.

### Manage models
- Tap **Model** (top-right). Each suggested model shows whether it's installed and
  which one is **active**. Tap to download (with a progress bar) or to switch the
  active model. **Download all** grabs every model in sequence. You can also paste a
  custom `.task` URL or import a local `.task` file.

### Normalize a video's audio
1. Open the app → **Video Normalizer**.
2. Pick a video, choose/adjust a preset, preview, then process. The output keeps the
   original video and writes a loudness-normalized audio track.

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
│  ├─ AiProvider.kt / OnDeviceLlm.kt  # on-device LLM (MediaPipe GenAI)
│  ├─ ModelManager.kt         # multi-model store + active pointer + downloads
│  ├─ Settings.kt             # prefs, suggested models, brand kit
│  └─ VisionTools.kt          # background removal + face detect (MediaPipe Vision)
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
overlay/title math) is written in **plain Kotlin with no Android imports**, so it can
be reused directly by the planned desktop builds.

---

## Tech stack

- **Kotlin**, **Jetpack Compose**, **Material 3**
- **MediaPipe Tasks** — GenAI (LLM), Vision (image segmenter + face detector)
- **Vosk** (`vosk-android`) — offline speech recognition
- Android **MediaCodec / MediaExtractor / MediaMuxer** for audio/video
- `minSdk 29`, `compileSdk 35`, Java 17, arm64-v8a

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
processed entirely on the device.

---

## License

TODO: add a license (e.g. Apache-2.0 or MIT). The bundled MediaPipe models and the
Vosk speech model are distributed under their own permissive licenses.
