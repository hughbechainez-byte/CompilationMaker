# Phase 16 - Streaming Scanner and Export Hardening

## What changed

- Fixed source-duration handling so scan/export logic no longer treats processing time as video length.
- Removed the silent 30 second safety-clip success path for empty scans.
- Added a streaming `MediaCodec`-backed frame provider with retriever fallback.
- Changed the coarse scan to process decoded frames sequentially instead of probing random timestamps first.
- Added multi-branch OCR preprocessing with raw evidence retention.
- Added export validation so incomplete or unplayable files are deleted and reported as failures.
- Added unit-test coverage for rotation mapping, segment merging, hysteresis, transition classification, boundary clamping, and source-duration selection.

## Verification

- `./gradlew.bat testDebugUnitTest`
- `./gradlew.bat assembleDebug`

