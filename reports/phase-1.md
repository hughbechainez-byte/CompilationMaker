# Phase 1 Report

- Status: partially completed
- Version tag target: v0.1-phase1-instrumentation
- Date: 2026-06-30

## What changed
- Added scan timing instrumentation in `app/src/main/java/com/example/compilationmaker/MainActivity.kt` for decode, ROI crop, preprocess, OCR, merge, export, and save phases.
- Added per-run scan report persistence as JSON in app private storage under:
  - `app filesDir/scan_reports/scan-<epoch>.json`
- Added scan summary and timing lines to developer-visible status feed.
- Added phase-1 clip test manifest and a minimal Android test class.
- Added baseline repo docs required by spec:
  - `docs/architecture.md`
  - `docs/testing.md`
  - `reports/performance.md`
  - `reports/known-issues.md`

## Files changed
- `app/src/main/java/com/example/compilationmaker/MainActivity.kt`
- `app/src/androidTest/java/com/example/compilationmaker/Phase1InstrumentationManifestTest.kt`
- `reports/phase-1.md`
- `reports/phase-1-test-manifest.md`
- `docs/architecture.md`
- `docs/testing.md`
- `reports/performance.md`
- `reports/known-issues.md`

## Build result
- Ran `./gradlew assembleDebug`.
- Result: failed.
- Reason: Android SDK not found. `local.properties` points at a non-existent directory.
- Error: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path`.

## Test result
- `./gradlew testDebugUnitTest` not executed because Android SDK configuration is missing (build prerequisite fails first).
- Added manifest smoke test file only; automated clip runs not possible yet.

## APK path
- Phase-named APK requested path: `releases/auto-comp-maker-v0.1-phase1-instrumentation-debug.apk`
- Copied from available debug artifact: `app/build/outputs/apk/debug/app-debug.apk` (note: build was blocked by SDK path at execution time, so artifact freshness should be verified after SDK setup).

## ADB install result
- `adb devices`: no device connected.
- `adb install -r releases/auto-comp-maker-v0.1-phase1-debug.apk` failed with: `adb.exe: no devices/emulators found`.
- No device-targeted install completed.

## Known issues
- Per-frame decode timing instrumentation currently calls `MediaMetadataRetriever.getFrameAtTime` once per sampled timestamp and may be noisy on some encoders.
- Export timing is measured around existing muxer code only; no new exporter path implemented yet.
- Scan report storage currently accumulates JSON files and does not prune older artifacts.
- Scan detection uses existing ML Kit flow (no new digit recognizer fallback path yet).

## Next phase notes
- Next phase will stabilize ROI persistence and project-specific storage with durable metadata.
