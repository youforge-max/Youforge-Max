# Youforge-Max — User Manual

A complete walkthrough of both tools, every control, model management, and
troubleshooting. For an overview and build instructions, see
[README.md](README.md).

> **Everything is offline.** The only time Youforge-Max touches the network is when
> *you* choose to download an AI model. After that it works in airplane mode.

---

## Contents

1. [Home screen](#home-screen)
2. [Thumbnail Maker](#thumbnail-maker)
   - [1. Pick a photo](#1-pick-a-photo)
   - [2. Clean up the photo (on-device AI)](#2-clean-up-the-photo-on-device-ai)
   - [3. Get a title](#3-get-a-title)
   - [4. Tweak the look](#4-tweak-the-look)
   - [5. Stickers](#5-stickers)
   - [6. Export](#6-export)
3. [Managing AI models](#managing-ai-models)
4. [Title from video (speech-to-text)](#title-from-video-speech-to-text)
5. [Video Normalizer](#video-normalizer)
6. [Where files go](#where-files-go)
7. [Troubleshooting](#troubleshooting)
8. [FAQ](#faq)

---

## Home screen

When you open Youforge-Max you see two cards:

- **Thumbnail Maker** — make a 1280×720 thumbnail.
- **Video Normalizer** — even out a video's audio loudness.

Tap a card to open the tool. The system **Back** gesture/button returns to this
home screen. Your in-progress thumbnail edit is **kept** when you bounce back to
home and re-enter — the photo, title, stickers and active model are all still
there.

---

## Thumbnail Maker

The screen is top-to-bottom: **Pick photo**, a live **preview**, the
**description** box, the three **title** buttons, the **Photo (on-device AI)** row,
the **stickers** palette, and the **tweak** panel ending in the **export** buttons.
A **Model** button sits in the top-right.

### 1. Pick a photo

Tap **Pick photo** and choose any image. Large sensor photos and HEIC files are
downscaled and decoded safely. Once a photo loads you'll see it in the 16:9 preview
and the button changes to **Pick another photo**.

### 2. Clean up the photo (on-device AI)

The **Photo (on-device AI)** row (only relevant on a 64-bit ARM device — see
[Compatibility](README.md#compatibility)):

- **Cut out · dark** — removes the background and drops the subject on a dark
  backdrop. High-contrast subject-on-dark is the strongest click-through trick.
- **Cut out · blur** — same cut-out, but the original background is blurred behind
  the subject instead of replaced with flat dark.
- **Auto-frame face** — finds the largest face and recrops so it sits in the upper
  third (classic reaction-thumbnail framing).
- **Restore photo** — undo any of the above and return to the original image.

If a cut-out or face-frame can't run, the status line names the reason — "no face
detected in this photo", say — and the photo is left unchanged.

### 3. Get a title

Type a sentence in **Describe it** (e.g. *"epic night wild camping storm, title WE
SURVIVED"*), then choose one of:

- **Suggest (AI)** — runs the **active on-device model** to write a title and pick a
  style. The button only says *(AI)* once a model is installed and active; otherwise
  it reads **Suggest (template)**. (Install a model via the **Model** button — see
  [below](#managing-ai-models).)
- **Template** — instant, fully offline. A deterministic generator picks a title
  style from keywords in your description. No model needed.
- **🎙 Title from video** — pick a video clip and let Youforge-Max transcribe its speech
  and turn that into the title (see [that section](#title-from-video-speech-to-text)).

You can always edit the generated title text by hand afterwards.

### 4. Tweak the look

Scroll to the tweak panel:

- **Effect strip** — tap a swatch to apply a text effect: **glow, neon, gradient,
  pop, bold outline, shadow, plain**. Each swatch is a tiny live render of that
  effect, so you see it before you pick it.
- **Glow / title colour** — colour swatches; the glow swatches appear for the glow
  and neon effects.
- **Rotation** — a slider from −180° to +180°, with a reset.
- **Title scale** — make the title bigger or smaller.
- **Position** — seven anchor presets (corners, edges, centre). Or ignore them and
  **free-transform** directly on the preview (next paragraph).
- **Style presets** — *Wild camp, Night, Punch, Warm, Clean* — five one-tap looks.
- **Save as my style (brand kit)** — remembers your current style so it's applied
  automatically to future thumbnails. Clear it any time.
- **Contrast** — a hint such as *"✓ Strong contrast (15.3:1)"* or a warning when the
  title is hard to read over the photo, with a one-tap **Fix** to white or black.

**Free transform on the preview:**
- **Tap** the title or a sticker to select it.
- **Drag** to move it.
- **Two-finger pinch** to scale, **twist** to rotate.

Manual line breaks in the title text are honoured (up to 3 lines).

### 5. Stickers

The sticker palette is grouped:

- **Shapes** — vector fish, fire, arrow, circle, and a red **SUBSCRIBE** pill.
- **Reactions / Hands / Marks / Outdoors / Extra** — ~60 emoji in labelled rows.

Tap a sticker to drop it on the preview, then drag / pinch / rotate it like the
title. Tap a sticker to select it and use its rotation slider.

### 6. Export

- **Export 1280×720 PNG** — saves the finished thumbnail to `Pictures/Youforge-Max`.
- **Export 3 A/B variants** — saves three versions with different title styles
  (`thumb_*_ab1..3.png`) so you can test which performs best.

---

## Managing AI models

Tap **Model** (top-right of Thumbnail Maker). The dialog lists the suggested models:

| Model | Format / engine | Size | Good for |
|---|---|---|---|
| Qwen2.5-1.5B-Instruct *(default)* | `.task` / MediaPipe | ~1.6 GB | Balanced quality, 6 GB+ RAM |
| Qwen2.5-0.5B-Instruct | `.task` / MediaPipe | ~0.55 GB | Fastest / lowest memory |
| TinyLlama-1.1B-Chat | `.task` / MediaPipe | ~1.15 GB | Small and snappy |
| Qwen2.5-1.5B-Instruct (GGUF) | `.gguf` / llama.cpp | ~1.1 GB | Smaller q4 file, GGUF ecosystem |
| Qwen2.5-0.5B-Instruct (GGUF) | `.gguf` / llama.cpp | ~0.47 GB | Smallest / fastest GGUF |

Youforge-Max runs **two engines**: `.task` models use **MediaPipe GenAI**; `.gguf`
models use the bundled **llama.cpp** native backend. You pick a model and Youforge-Max
uses the right engine automatically. The GGUF backend keeps the model **warm** (only
the first suggestion per session pays the load cost) and constrains its output with a
grammar so the title JSON is always valid.

For each row you'll see whether it's **installed** and which is **● active**:

- **Tap an un-installed model** → downloads it (with a progress bar). Every download
  is **SHA-256 verified** and rejected on mismatch. All suggested models are ungated
  and need no login.
- **Tap an installed model** → makes it the active model (instant, no re-download).
- **Download all** → fetches every suggested model in sequence.
- **Paste a custom URL** → download any `.task` or `.gguf` (e.g. a gated Gemma 3 /
  Llama 3.2 link you have access to, or any ungated Hugging Face GGUF). The format
  is inferred from the URL/file.
- **Pick local file** → import a `.task`/`.gguf` you already downloaded on a
  computer — no network, no re-download. The format is auto-detected from the file's
  magic bytes.

Models live in the app's private storage and are reused offline. Keep several side
by side and switch freely.

> **Tip:** the model only writes the *title text* — the renderer does all the visual
> work. A 0.5–1.5B instruct model is more than enough; bigger models are slower for
> no real visual gain.

---

## Title from video (speech-to-text)

1. In Thumbnail Maker, tap **🎙 Title from video (on-device)**.
2. Pick a video clip that contains speech.
3. On first use, Youforge-Max downloads the **~40 MB English Vosk speech model** once
   (progress shown). After that it's offline forever.
4. The app decodes the clip's audio to 16 kHz mono, transcribes it locally, puts the
   transcript in the description box, and generates a title from it.

Only the first ~3 minutes of audio are used. Recognition is English; accuracy is
good for clear speech and rougher for noisy or accented audio — treat the result as
a starting point and edit the title.

---

## Video Normalizer

A fully offline audio loudness tool for video files.

1. Open **Video Normalizer** from home.
2. **Pick video…** — choose a clip. The video track is copied through untouched;
   only the audio is reprocessed.
3. **Preview** — plays a 60-second window through the same DSP. Adjust any control
   while it plays and the change is audible live.
4. Controls:
   - **Loudness normalize** (on/off) and **Target** in **LUFS** (EBU R128 integrated
     loudness; −14 LUFS is a common streaming target).
   - **Input gain** (dB).
   - **Master enable** for the whole processing chain.
   - **5-band** compressor with a brick-wall limiter and a true-peak ceiling.
   - **Presets** for quick starting points.
5. Process to write a new file with the normalized audio and the original video.

---

## Where files go

| What | Location | Notes |
|---|---|---|
| Exported thumbnails | `Pictures/Youforge-Max/thumb_*.png` | Visible in your gallery |
| LLM models (`.task` / `.gguf`) | App private storage | Never in shared storage or the repo |
| Vosk speech model | App private storage | Downloaded once, unpacked locally |
| Bundled vision models | Inside the APK | Ship with the app, work offline |

Uninstalling the app removes the downloaded models.

---

## Troubleshooting

**"Suggest (AI)" is missing / says "Suggest (template)".**
No model is active. Open **Model** and download or activate one. Template mode works
without a model.

**A model download fails / errors.**
Check connectivity for the one-time download. The default suggested models are
ungated; a *custom URL* that returns 401 is a **gated** model (needs a Hugging Face
login) — download it on a computer and use **Pick local file** instead. A
download that fails right after completing is a **SHA-256 mismatch** (corrupt or
wrong file) — retry, or import a verified copy locally.

**"Cut out" or "Auto-frame face" failed.**
The status line names the reason. "No face detected in this photo" means exactly
that — try a photo where the face is larger and clearly lit. Anything else is a
real error; it is also written to logcat under the tag `VisionTools`.

Builds before **v1.5.1** failed here on *every* photo, in release builds only. See
the changelog.

**The AI title takes a long time.**
On-device LLM speed depends on the chip and the model size. Use **Qwen2.5-0.5B** on
slower/low-RAM devices, or just use **Template** for an instant result.

**The photo loaded but the rest of the screen seemed empty.**
Scroll down — the description, title buttons, stickers and tweak panel are below the
preview. (On large tablets in landscape the preview is capped so the controls stay
in view.)

**My edit disappeared.**
It shouldn't — the thumbnail edit is preserved across trips to the home screen.
Picking a *new* photo or fully closing the app does reset it.

---

## FAQ

**Does it send my photos/videos anywhere?**
No. There is no backend and no analytics. The only network use is downloading model
files you explicitly request.

**Do I need an account or subscription?**
No. There are no accounts, no subscriptions, no rate limits.

**Can it run fully offline?**
Yes. Once your chosen models are downloaded, everything works in airplane mode. The
template title generator and the bundled vision models work offline from the very
first launch.

**Which devices work?**
Any 64-bit ARM phone/tablet on Android 10+ with ≥4 GB RAM (≥6 GB recommended for the
on-device LLM). See [Compatibility](README.md#compatibility).

**Is there a desktop version?**
Not yet. The non-AI core is written in plain Kotlin specifically so desktop builds
(Linux/macOS/Windows) can reuse it — it's on the roadmap.
