# Scanner Reliability Audit

## Baseline failure path

The app was successfully exporting compilations, but the scan path could collapse to a short clip because the worker treated an empty transition result as a success path and fell back to a 30 second safety segment.

The root causes found in the code were:

- `CompilationWorker` used processing time as if it were source duration.
- The scan pipeline relied on sparse timestamp probes around `MediaMetadataRetriever` frames.
- OCR was effectively a single raw integer parse from one bitmap branch.
- The report format did not clearly separate source duration, scan time, and frame-provider fallback.
- No-transition outcomes were not explicit, so scan failure could look like a successful export.

## What was changed

- The worker now consumes actual source duration from the scan result.
- Empty scan results now return an explicit no-transition failure instead of silently exporting a short clip.
- The scanner now prefers a streaming frame provider backed by `MediaCodec` and falls back to retriever mode only if needed.
- Coarse scanning processes decoded frames sequentially instead of probing random timestamps as the primary path.
- OCR now tries multiple preprocessing branches and records the raw evidence.
- Export validation now rejects files that do not report duration or a video track.
- Scan reports now record frame provider, fallback reason, and coarse sample count.

## Remaining device checks

- Verify `MediaCodec` decoding on a real phone with long videos and variable frame rate sources.
- Confirm scan timing on the target video corpus.
- Confirm export validation on the actual device file system and installed player apps.

