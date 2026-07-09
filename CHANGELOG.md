# Changelog

All notable changes to Youforge-Max. Release APKs are `arm64-v8a`, R8-minified,
and signed. Each heading is a git tag; the APK attached to that tag's GitHub
Release carries the `versionCode` shown.

From **v1.5.1** onward, every release bumps `versionCode` and sets `versionName`
to the tag, so heading, tag, and APK agree. Earlier tags did not: `versionCode`
stayed at **31** and `versionName` at `1.1-max-p19` for all of `v1.1-max-p19`
through `v1.5-max-p23`, leaving those five builds indistinguishable to Android.

## v1.5.1 (versionCode 32) — photo tools fixed in release builds

- **Fixed:** background cut-out (dark/blur) and auto-frame face failed in *every*
  release build, reporting "couldn't process" for any photo. R8 renamed
  `com.google.common.flogger.FluentLogger`, and MediaPipe's `Graph.<clinit>` calls
  `FluentLogger.forEnclosingClass()`, which finds its caller by matching Flogger's
  own class name against the live stack. The rename made that lookup throw
  `IllegalStateException: no caller found on the stack`, so `Graph` never
  initialised and both `ImageSegmenter` and `FaceDetector` threw
  `ExceptionInInitializerError` on creation. `proguard-rules.pro` now keeps
  Flogger's names; the existing `com.google.mediapipe.**` rule never covered it,
  as Flogger lives in its own package. Debug builds were unaffected — nothing is
  renamed there, which is why this survived so long.
- **Errors are now honest:** `VisionTools` used to swallow every exception and
  return null, leaving the UI to guess. It records `lastError`, logs under the
  `VisionTools` tag, and the status line shows the actual reason — "no face
  detected in this photo" is now distinguishable from a genuine failure.
- **Editor:** migrated `EditedMediaItemSequence` to the Builder API.
- **Deps:** Media3 1.8.1 → **1.10.1** (common, effect, ui); Gradle wrapper 9.6.1.
- **Versioning:** `versionCode` bumped for the first time since `v1.1-max-p19`.
- Verified with an instrumented probe run against a minified release build on an
  x86_64 emulator, then confirmed on hardware.

## v1.5-max-p23 (versionCode 31) — preview playback

- Fixed editor preview playback: effects pipeline and control tap handling.

## v1.4-max-p22 (versionCode 31) — dark/cyan editor design

- Implemented the dark/cyan design.
- Fixed the blank preview by switching to a `TextureView`.

## v1.3-max-p21 (versionCode 31) — YouCut-style editor layout

- Preview stage + timeline + tool bar.

## v1.2-max-p20 (versionCode 31) — editor redesign

- Full-bleed preview and a tabbed bottom sheet.

## v1.1-max-p19 (versionCode 31) — first tagged release

Rolls up the work previously tracked as the untagged `r15`–`r17` milestones,
whose stated `versionCode` values (15/16/17) never reached an APK.

**Toolchain** (was r17)

- Migrated to **Kotlin 2.4.0** and the `org.jetbrains.kotlin.plugin.compose`
  Compose compiler plugin (replaces the pinned `kotlinCompilerExtensionVersion`).
  `kotlinOptions` → the Kotlin 2.x `compilerOptions` DSL.
- Material 3 / Compose BOM bumped to **2026.06.00**.
- Resolves the held Dependabot updates (compose-bom, kotlin) that could only land
  together with the toolchain migration; pinned `tasks-vision` to the 0.10.x line
  (ignored the mis-ranked date-scheme version).

**GGUF / llama.cpp backend** (was r16)

- **New on-device engine:** `.gguf` models via a bundled **llama.cpp** native (NDK)
  backend, alongside the existing MediaPipe `.task` engine. Adds two suggested GGUF
  models (Qwen2.5-0.5B / 1.5B, q4_k_m) and opens the whole ungated HF GGUF ecosystem
  via the custom-URL field.
- **GBNF grammar** constrains GGUF output to valid OverlaySpec JSON (uppercase
  title, locked style enums, `#RRGGBB` colours).
- **Keep-warm** model cache: only the first suggestion per session pays the load cost.
- Model picker auto-detects format (`.task` vs `.gguf`) from magic bytes on import.

**Security hardening** (was r15)

- **HTTPS-only:** `usesCleartextTraffic=false` + a network security config.
- **Model integrity:** SHA-256 verification of every model download; Zip-Slip guard
  on archive extraction.
- **R8:** release builds minified + resource-shrunk with ProGuard keep rules.
- `allowBackup=false`. Added CI (build + Trivy scan) and Dependabot.

---

## Device & compatibility notes

- **Target:** Android 10+ (API 29 → 35), **64-bit ARM (`arm64-v8a`)** only for
  releases. Debug builds add `x86_64` for the emulator; every feature works there,
  vision included (`tasks-vision` does ship an x86_64 native library — earlier
  editions of this file claimed otherwise).
- **Primary test device:** **Samsung Galaxy Tab S7+** (Snapdragon 865+, 8 GB) —
  full feature set confirmed.
- **RAM guidance:** < 4 GB → template generator only; 4–6 GB → Qwen2.5-0.5B;
  6–8 GB → Qwen2.5-1.5B (default) / TinyLlama; 8 GB+ → any, incl. custom models.
- **LLM speed** scales with the SoC; the offline template path is always instant.
- v1.5.1 is verified on the Tab S7+: both photo tools work.
