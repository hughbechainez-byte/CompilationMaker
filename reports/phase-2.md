# Phase 2 Report

- Status: partially completed
- Version tag target: v0.2-phase2-roi-workflow
- Date: 2026-06-30

## What changed
- Added video-scoped ROI persistence in SharedPreferences with metadata (`uri`, `rotation`, dimensions, ROI percentage window).
- Restores saved ROI automatically when same video is selected again and normalizes across rotation differences.
- Added video metadata extraction on selection (`VIDEO_ROTATION`, width, height).
- Switched ROI preview capture to `getScaledFrameAtTime(...)` (UI preview only) with fallback to `getFrameAtTime(...)`.
- Added ROI rotation-aware normalization for preview and scan-side OCR workflows.
- Persisted ROI updates when ROI values are edited or moved.

## Files changed
- `app/src/main/java/com/example/compilationmaker/MainActivity.kt`
- `reports/phase-2.md`

## Build result
- Ran `./gradlew assembleDebug`.
- Result: failed.
- Reason: Android SDK location invalid in `local.properties` (`sdk.dir` points to missing directory).

## Test result
- No automated tests added in this phase.

## APK path
- `releases/auto-comp-maker-v0.2-roi-workflow-debug.apk` (copied from latest `app-debug.apk`).
- Note: build failure means APK freshness should be confirmed after SDK path fix.

## ADB install result
- `wsl -e adb devices` returned device: `57110DLCH001ZD`.
- `wsl -e adb install -r /mnt/c/Users/blowb/Documents/compilationmaker/releases/auto-comp-maker-v0.2-phase2-roi-workflow-debug.apk` returned `Success`.

## Known issues
- No fresh build due invalid SDK path, so timing and scan behavior should be revalidated after SDK setup.
- ROI rotation normalization is based on source rotation metadata and saved metadata assumptions.
- Export pipeline remains unchanged from prior phase.

## Next phase notes
- Proceed to Phase 3: add cheap ROI difference gate and digit-focused recognition refinements while keeping ML Kit as fallback path.
