# Phase 15 - Adaptive scanner architecture

## What changed

- Added a `FrameProvider` abstraction with a retriever-backed implementation so scanner logic no longer depends directly on timestamp frame grabs.
- Reworked the scan pass to use adaptive stable-region skipping instead of a fixed step for the whole video.
- Added candidate reasons, candidate sample counts, accepted `TransitionMark` records, OCR call counters, baseline sample estimates, throughput metrics, skipped-stable-time accounting, and release-gate booleans in scan reports.
- Added dense-window transition refinement through `FrameProvider.decodeWindow(...)`, with binary probing retained as fallback.
- Added status-feed metrics after scan: throughput, OCR calls versus estimated baseline samples, OCR reduction percent, and transition mark count.
- Bumped app version from `0.14.6` to `0.15.0`.

## Verification

- `./gradlew.bat assembleDebug` completed successfully.

## Notes

- The first `FrameProvider` implementation wraps `MediaMetadataRetriever`; the scanner now has the API boundary needed for a future true `MediaExtractor + MediaCodec` provider.
- Speed gains in this phase come from adaptive stable skipping and lower OCR volume, with JSON reports recording the counters needed to compare against `0.14.6` on the same videos/device.
