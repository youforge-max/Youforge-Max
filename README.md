# YouForge (`eu.cisodiagonal.youforge`)

Offline Android creator toolkit for **@campingwithcd**. One app, a home screen, two
tools — merged from the former `thumbforge` and `five-band-video` projects.

## Tools

**Thumbnail Maker** (was ThumbForge)
- Pick a photo, describe it in plain language → on-device LLM (Qwen2.5-1.5B via
  MediaPipe, ungated, downloaded once) writes the title/colours/placement.
- Composites a 1280×720 overlay; offline Template fallback when no model.
- Sticker layer: Fish, Fire, Arrow, Circle-highlight, Subscribe pill + emoji —
  tap to add, drag to move, scale, delete.
- Export PNG → `Pictures/ThumbForge`.

**Video Normalizer** (was five-band-video)
- Offline 5-band compressor + limiter + LUFS normalize for video audio (same DSP
  as the live five-band-comp app). MediaCodec decode/encode + MediaMuxer copies
  the video stream verbatim.
- 60 s looped live preview, presets, SAF in/out.

## Structure

| Package | Contents |
|---------|----------|
| `eu.cisodiagonal.youforge` | `MainActivity` (single launcher) + `YouForgeApp` home/nav |
| `…​.thumb` | Thumbnail Maker (`ThumbForgeScreen`, renderer, AI, stickers, model mgr) |
| `…​.video` | Video Normalizer (`VideoNormalizerScreen`, DSP, MediaProcessor, presets) |

Navigation: home screen → tool; system back / `←` returns home (`BackHandler`).

## Build

```bash
export PATH="$HOME/tools/gradle-8.9/bin:$PATH"
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

Debug APK ~38 MB, arm64-v8a only (MediaPipe LLM native lib dominates). Signed
release: set `RELEASE_STORE_FILE`/`_PASSWORD`/`RELEASE_KEY_ALIAS`/`_PASSWORD`
(gradle props or env), then `./gradlew assembleRelease`.

## Icon

Red tile + white play triangle + forge sparks (adaptive vector). **UNTESTED on
device.**
