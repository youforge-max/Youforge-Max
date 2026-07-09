# Changelog

All notable changes to Youforge-Max. Versions map to `versionName` / `versionCode`
in `app/build.gradle.kts`. Release APKs are `arm64-v8a`, R8-minified, and signed.

## v1.5.1 — photo tools fixed in release builds

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
- Verified with an instrumented probe run against a minified release build on an
  x86_64 emulator, then confirmed on hardware.

## r17 (versionCode 17) — Kotlin 2.4 toolchain

- **Build:** migrated to **Kotlin 2.4.0** and the `org.jetbrains.kotlin.plugin.compose`
  Compose compiler plugin (replaces the pinned `kotlinCompilerExtensionVersion`).
  `kotlinOptions` → the Kotlin 2.x `compilerOptions` DSL.
- **Compose:** Material 3 / Compose BOM bumped to **2026.06.00**.
- **Deps:** resolves the held Dependabot updates (compose-bom, kotlin) that could
  only land together with the toolchain migration; pinned `tasks-vision` to the
  0.10.x line (ignored the mis-ranked date-scheme version).
- No behavioural change to either tool — toolchain/dependency release.

## r16 (versionCode 16) — GGUF / llama.cpp backend

- **New on-device engine:** `.gguf` models via a bundled **llama.cpp** native (NDK)
  backend, alongside the existing MediaPipe `.task` engine. Adds two suggested GGUF
  models (Qwen2.5-0.5B / 1.5B, q4_k_m) and opens the whole ungated HF GGUF ecosystem
  via the custom-URL field.
- **GBNF grammar** constrains GGUF output to valid OverlaySpec JSON (uppercase
  title, locked style enums, `#RRGGBB` colours).
- **Keep-warm** model cache: only the first suggestion per session pays the load cost.
- Model picker auto-detects format (`.task` vs `.gguf`) from magic bytes on import.

## r15 (versionCode 15) — security hardening

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
  full feature set confirmed through r16.
- **RAM guidance:** < 4 GB → template generator only; 4–6 GB → Qwen2.5-0.5B;
  6–8 GB → Qwen2.5-1.5B (default) / TinyLlama; 8 GB+ → any, incl. custom models.
- **LLM speed** scales with the SoC; the offline template path is always instant.
- v1.5.1 is verified on the Tab S7+: both photo tools work.
