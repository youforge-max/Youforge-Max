# YouForge Max — video editor (YouCut-style)

Goal: a free, **offline, on-device, no-watermark** video editor inside YouForge,
alongside the existing Thumbnail Maker + Video Normalizer. Branch: `youforge-max`.

## Why it's feasible

YouCut's core is trim / split / merge / speed / text+stickers / music / filters /
transitions / export ≤4K, with no watermark. Every piece maps onto Android's official
**androidx.media3 Transformer + Composition** engine (MediaCodec-based, Apache-2.0,
fully offline, no FFmpeg, no watermark):

| YouCut feature | YouForge Max approach |
|----------------|------------------------|
| Trim / cut / split | `MediaItem.ClippingConfiguration` per clip |
| Merge / join | `EditedMediaItemSequence` (one track) |
| Speed 0.2–100× | `SpeedChangeEffect` on an `EditedMediaItem` |
| Text / stickers | Media3 `OverlayEffect` (`TextOverlay`/`BitmapOverlay`) — **reuse YouForge's overlay/title renderer + sticker set** |
| Filters / FX | Media3 `GlEffect` / `RgbMatrix` / LUT |
| Transitions | crossfade via overlapping `EditedMediaItem`s (Media3 1.4+) |
| Music / volume | extra audio `EditedMediaItemSequence` + `Effects` volume; **reuse the 5-band DSP from Video Normalizer for mastering** |
| Export ≤4K, no watermark | `Transformer` H.264/HEVC, app sets resolution; never writes a watermark |
| Live preview | `ExoPlayer` + `PlayerView` (+ `ExoPlayer.setVideoEffects` for previewing effects) |

So we're **not** reimplementing a codec stack — we orchestrate Media3 and reuse two
things YouForge already has: the **Compose UI** and the **overlay renderer + audio DSP**.

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
- **Deferred:** per-clip speed (`SpeedChangeEffect`), transitions, redo.

## Notes / risks

- Transformer must start on a Looper thread (handled: main thread + progress poll).
- HDR / 10-bit and mixed codecs can need tone-mapping flags — add when hit.
- Keep the editor a single track in early phases; multi-layer is a later lift.
- Current YouForge functionality (thumbnail, normalizer, GGUF/Vosk/vision) is
  unchanged — Max is purely additive. versionName `1.1-max-p1`, versionCode 18.
