# Phase 3 Report

- Status: partially completed
- Version tag target: v0.3-digit-recognizer
- Date: 2026-06-30

## What changed
- Added checkpoint-mode refinement in `VideoCompilationEngine.findNumberTransitionSegments` to recover missed transitions in sparse scan mode.
- For each checkpoint sample in `ScanMode.Checkpoints3Min`, if the direct checkpoint sample does not detect a transition, the engine now scans a 1-second stepped local window around that checkpoint (`±min(90s, step/2)` for the selected checkpoint interval).
- Local window transitions update `previousNumber` and emit transition timing/progress for better reliability.
- Added logging-backed diagnosis: confirmed previous behavior produced `Detected transitions: 0` in checkpoint mode with no additional OCR failure logs from `CompilationEngine`.

## Files changed
- `app/src/main/java/com/example/compilationmaker/MainActivity.kt`
- `reports/phase-3.md`

## Build result
- Ran `./gradlew assembleDebug`.
- Result: failed.
- Reason: Android SDK path invalid in `local.properties` (`sdk.dir` points to missing directory).

## Test result
- No unit/instrumented tests were added in this phase.
- Device log review via WSL adb:
  - `wsl -e adb logcat -d -s "CompilationMaker" "CompilationEngine"`
  - Observed `Detected transitions: 0` for 3-minute checkpoint runs.

## APK path
- `releases/auto-comp-maker-v0.3-digit-recognizer-debug.apk`
- Note: APK was copied from current artifact because local build is blocked by SDK config.

## ADB install result
- `wsl -e adb devices` returned connected device: `57110DLCH001ZD`.
- `wsl -e adb install -r /mnt/c/Users/blowb/Documents/compilationmaker/releases/auto-comp-maker-v0.3-digit-recognizer-debug.apk` returned `Success`.

## Known issues
- `./gradlew assembleDebug` is still blocked by missing SDK path.
- Checkpoint refinement may increase scan work relative to pure checkpoint sampling and is a stopgap until full local-difference gate and digit-focused recognizer are implemented.

## Next phase notes
- Complete Phase 3 by adding explicit ROI difference gates and digit-focused recognition/fallback behavior.
