# Youforge-Max — video editor (YouCut-style)

Goal: a free, **offline, on-device, no-watermark** video editor inside Youforge-Max,
alongside the existing Thumbnail Maker + Video Normalizer. Branch: `youforge-max`.

## Why it's feasible

YouCut's core is trim / split / merge / speed / text+stickers / music / filters /
transitions / export ≤4K, with no watermark. Every piece maps onto Android's official
**androidx.media3 Transformer + Composition** engine (MediaCodec-based, Apache-2.0,
fully offline, no FFmpeg, no watermark):

| YouCut feature | Youforge-Max approach |
|----------------|------------------------|
| Trim / cut / split | `MediaItem.ClippingConfiguration` per clip |
| Merge / join | `EditedMediaItemSequence` (one track) |
| Speed 0.2–100× | `SpeedChangeEffect` on an `EditedMediaItem` |
| Text / stickers | Media3 `OverlayEffect` (`TextOverlay`/`BitmapOverlay`) — **reuse Youforge-Max's overlay/title renderer + sticker set** |
| Filters / FX | Media3 `GlEffect` / `RgbMatrix` / LUT |
| Transitions | crossfade via overlapping `EditedMediaItem`s (Media3 1.4+) |
| Music / volume | extra audio `EditedMediaItemSequence` + `Effects` volume; **reuse the 5-band DSP from Video Normalizer for mastering** |
| Export ≤4K, no watermark | `Transformer` H.264/HEVC, app sets resolution; never writes a watermark |
| Live preview | `ExoPlayer` + `PlayerView` (+ `ExoPlayer.setVideoEffects` for previewing effects) |

So we're **not** reimplementing a codec stack — we orchestrate Media3 and reuse two
things Youforge-Max already has: the **Compose UI** and the **overlay renderer + audio DSP**.

## Phases

- **P1 (DONE, MVP):** import multiple clips, per-clip trim, reorder/remove, ExoPlayer
  preview, **export merged MP4** via Transformer. `editor/` package:
  `EditorModels`, `EditorExporter` (Composition export), `VideoEditorScreen`. New home
  card "Video Editor (beta)". Builds green; **untested on device**.
- **P2:** per-clip **speed** (`SpeedChangeEffect`) + volume; frame-thumbnail timeline
  strip (`MediaMetadataRetriever`/`Bitmap` extraction) instead of the list.
- **P3:** **text + sticker overlays** via `OverlayEffect`, driven by the existing
  `thumb` overlay model/renderer (one shared overlay spec across thumbnail + video).
- **P4:** **music track** (second audio sequence) + ducking, mastered through the
  Video Normalizer DSP; fade in/out.
- **P5 (DONE):** colour **filters** (B&W/Vivid/Warm/Cool/Punch via Media3
  RgbFilter/HslAdjustment/RgbAdjustment/Contrast) at export + live preview; **4K**
  export resolution. Transitions/crossfade still deferred (Media3 crossfade fiddly).
- **P6 (DONE):** live filter preview (`ExoPlayer.setVideoEffects`); **undo**;
  **project save/load** (clips+trim+mute+title+music+filter+resolution → filesDir).
- **Fit:** all control rows use `FlowRow` (wrap) + the screen scrolls, so nothing
  runs off-screen (verified on Tab S7+ geometry 2800×1752).
- **Per-clip speed (DONE):** `SpeedChangeEffect` + `SonicAudioProcessor` (0.25×–2×).
- **Transitions (DONE):** `Fade` — each clip fades in/out of black at its edges via a
  time-varying black `BitmapOverlay` (Media3 has no built-in clip crossfade).
- **Reorder (DONE):** move clips left/right in the timeline.
- **Aspect / canvas (DONE):** output ratio chips — Source / 16:9 / 9:16 / 1:1 / 4:5 —
  crop-fill via `Presentation.createForWidthAndHeight(w, h, LAYOUT_SCALE_TO_FIT_WITH_CROP)`
  (height = resolution.height, width = height·w/h rounded even). Applied at export;
  preview stays source-shape. Saved in project (v3 format). versionName `1.1-max-p7`,
  versionCode 19.
- **Backlog cleared (DONE, p8):**
  - **Per-clip volume** — `ChannelMixingAudioProcessor` identity matrix scaled 0–2× (mono+stereo), slider in the clip panel (hidden when muted).
  - **Rotate** — `ScaleAndRotateTransformation` 90° steps per clip.
  - **Stickers-on-video** — emoji/text burned via `BitmapOverlay.createStaticBitmapOverlay` anchored on the output canvas (NDC from normalised x/y); add field + X/Y position sliders + clear.
  - **Redo** — second stack mirroring undo; `edit()` clears redo.
  - **Live aspect-crop preview** — `PlayerView` shaped to the chosen ratio + `RESIZE_MODE_ZOOM` (crop-fill); Source = `RESIZE_MODE_FIT`.
  - Project save/load bumped to v4 (clip volume+rotation, sticker lines `S|…`). versionName `1.1-max-p8`, versionCode 20.
- **Backlog cleared (DONE, p9):**
  - **Letterbox aspect** — non-SOURCE ratios get a Letterbox toggle: `Presentation.LAYOUT_SCALE_TO_FIT` (black bars) vs crop-fill; preview honours it via `RESIZE_MODE_FIT`.
  - **Per-sticker select/delete** — chip row selects any existing sticker (was only on-add); per-sticker Delete button.
  - **Per-sticker timing** — `Sticker.startMs/endMs` (−1 = until end); export uses a `TimedSticker` `BitmapOverlay` that alpha-gates by output-timeline `presentationTimeUs`; start/end sliders in the sticker panel.
  - Project save/load bumped to v5 (header `letterbox`, sticker lines `S|…|startMs|endMs`); v4/older still load.
- **Slide/zoom transitions (DONE, p10):** `MatrixTransformation` (time-varying 2D matrix in NDC, clip-local timeline) — `SlideTransition` translates the frame in from left / out to right over black; `ZoomTransition` punch-zooms in at the start and out at the end (scale about centre). Wired alongside FADE in a `when` on `project.transition`; chips auto-populate from the enum. versionName `1.1-max-p10`, versionCode 22.
- **Per-sticker drag (DONE, p11):** stickers now render in the live preview (were export-only) and each is draggable to reposition — drag updates normalised x/y (also selects it); sliders still work. Preview maps `sizePx` (720-canvas-relative) to preview height. versionName `1.1-max-p11`, versionCode 23.
- **True crossfade (DONE in code, p12 — UNVERIFIED on device):** bumped Media3 1.4.1 → **1.7.1** for `EditedMediaItemSequence.Builder.addGap`. Implemented A/B-roll dissolve: clips alternate between two video sequences staggered to overlap by `xUs` (timeline compressed per cut); the top (odd-index) sequence ramps its alpha in/out via a custom `RampAlphaShaderProgram` (reuses Media3's bundled `shaders/fragment_shader_alpha_scale_es2.glsl`, drives `uAlphaScale` from `presentationTimeUs`) so it dissolves over the full-alpha bottom sequence. Math holds for both even→odd and odd→even cuts (the *top* clip always ramps, bottom stays full). `xUs` clamped to ½ the shortest clip (cap 800 ms) else falls back to a cut. Also migrated `OverlaySettings.Builder` → `StaticOverlaySettings.Builder` (1.7 refactor; `OverlaySettings` moved to `androidx.media3.common`). **Compiles + builds green; not run — no device.** Risks to verify on device: (1) 1.7.1 may reject a video sequence that *starts* with a gap without the force-track API (added in 1.8+, which needs compileSdk 36); (2) multi-sequence alpha compositing result; (3) audio overlap during the dissolve. versionName `1.1-max-p12`, versionCode 24.
- **Crossfade hardening (p13):** bumped Media3 1.7.1 → **1.8.1** and **compileSdk 35 → 36** (installed `platforms;android-36`; AGP stays 8.6.1 with `android.suppressUnsupportedCompileSdk=36` in gradle.properties — warning only, no AGP/Gradle bump). Re-added `experimentalSetForceVideoTrack(true)` + `experimentalSetForceAudioTrack(true)` to both crossfade sequences so the leading/interior **gaps render as blank video + silent audio** (the API blocker behind the leading-gap risk). Builds green. Still **device-unverified** (no device): confirm the dissolve visually + audio behaviour during overlap. versionName `1.1-max-p13`, versionCode 25.
- **Remaining backlog:** device-verify crossfade dissolve + audio; everything else in the original Max backlog is cleared.

## Notes / risks

- Transformer must start on a Looper thread (handled: main thread + progress poll).
- HDR / 10-bit and mixed codecs can need tone-mapping flags — add when hit.
- Keep the editor a single track in early phases; multi-layer is a later lift.
- Current Youforge-Max functionality (thumbnail, normalizer, GGUF/Vosk/vision) is
  unchanged — Max is purely additive. versionName `1.1-max-p1`, versionCode 18.
