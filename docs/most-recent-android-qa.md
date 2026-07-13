# Most Recent Android QA Note

**Anyone working on Compilation Maker must read this note before making updates. After every update is finished, the updated app must be tested and this note must be updated with the result.**

## Current test

- Status: BLOCKED — test runner cannot yet complete fixture selection
- App/version: current workspace build
- Fixture: `C:\Users\blowb\Desktop\comp test vids\compilation test video A.mp4`
- Expected reference: `C:\Users\blowb\Desktop\comp test vids\compilation test video B.mp4`
- Test target: Windows Android Emulator, `CompilationMaker_API35`

## Results

- Date: 2026-07-12
- Build: current workspace debug build; `:app:assembleDebug` succeeded.
- Emulator: `CompilationMaker_API35`, API 35, `emulator-5554`.
- Fixture staging: passed. Video A and Video B were pushed to `/sdcard/Download`.
- Picker: the runner reached the local Downloads root and displayed Video A, but the automated run did not return to the app; no compilation was started.
- OCR/scan/export: not reached, so no compilation result can be compared with Video B.
- Failure: `scan profile picker not found` in `run-release-qa.ps1` after the picker interaction. Manual inspection confirmed DocumentsUI had Video A visible in Downloads, so the remaining issue is picker-return/UI synchronization rather than missing fixture data.
- Conclusion: **FAIL / incomplete test**. Do not treat this as evidence that the app can or cannot reproduce Video B until the picker handoff is made deterministic.

## Next required action

Make the test fixture handoff deterministic (test-only content URI/provider or a reliable DocumentsUI confirmation path), then rerun the complete A→compilation→output verification flow and update this note.
