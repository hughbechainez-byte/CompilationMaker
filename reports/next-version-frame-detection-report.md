# Next Version Report - Frame-Level Digit Change Detection

## Source material

This report compares the findings in `C:\Users\blowb\Downloads\Accelerating Frame-Level Detection of Digit Overlay Changes in Video Compilation Apps.pdf` against the current app in this repository.

Current app version observed in `app/build.gradle.kts`: `0.14.6`.

## Executive recommendation

The next version should not be a general UI polish release. It should be a scanner architecture release.

The PDF's central finding is that the best production design is a coarse-to-fine pipeline:

1. Decode sparsely.
2. Crop the number ROI.
3. Compute cheap visual change signals.
4. Open candidate windows only where the ROI appears to change.
5. Run dense local refinement only inside those candidate windows.
6. Use OCR or a digit classifier only as the final verifier.
7. Emit the first frame or timestamp where the new visible number is stable.

The current app already implements part of that idea, but it is still held back by repeated `MediaMetadataRetriever` frame extraction, an overly simple 8x8 luma-only ROI signature, generic ML Kit OCR, limited evidence in scan reports, and a preview UI that only reviews the final compilation rather than the detected transition marks.

The next version should add these concrete features:

1. A reusable windowed frame provider backed by `MediaExtractor + MediaCodec`, used for scan and local refinement.
2. Rich ROI feature extraction beyond average luma: 16x16 luma grid, edge energy, contrast, binarized foreground area, changed-pixel fraction, and optional per-digit cell scores.
3. A structured transition pipeline with explicit `RoiSample`, `CandidateWindow`, `ConfirmedTransition`, `TransitionMark`, and `OcrEvidence` models.
4. Dense local refinement that walks decoded frames or near-frame samples inside candidate windows, instead of relying primarily on binary search over timestamp seeks.
5. A recognizer abstraction that keeps ML Kit as fallback but adds digit-oriented recognition paths: template matching for fixed overlays and a later custom digit classifier option.
6. Expanded scan reports and in-app transition review so the user can inspect each detected change before saving.
7. A benchmark/test corpus workflow that measures event recall, temporal error, false positives per hour, and scan throughput.

## What the app currently offers

### User-facing workflow

The current app lets the user:

- Select a video.
- Preview/scrub the source video.
- Capture a frame and manually place or resize a detection ROI.
- Enter ROI percentages directly.
- Pick quality, output format, scan interval, and transition style.
- Build a compilation.
- Preview the generated compilation.
- Save or discard the generated compilation.
- Check for updates.
- Watch a status feed and progress bar.

Relevant files:

- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/java/com/example/compilationmaker/MainActivity.kt`

### Current scanner behavior

The current scanner is implemented in `VideoCompilationEngine.findNumberTransitionSegments(...)`.

It currently:

- Uses `MediaMetadataRetriever` for analysis frame extraction.
- Samples along the timeline using configured modes:
  - `FastChangeMap`
  - `AccurateChangeMap`
  - `DenseRefine`
- Builds a cheap ROI signature from an 8x8 luma grid.
- Compares each signature to the previous signature using mean absolute difference.
- Opens candidate windows when the signature delta crosses a threshold or when a periodic probe is due.
- Uses ML Kit text recognition on cropped/scaled ROI frames.
- Parses the first integer from OCR text with a regex.
- Detects before/after number differences around candidate windows.
- Has special handling for the first visible `1`.
- Uses binary-search-style timestamp refinement to narrow transitions.
- Builds segments as roughly `transition - 10s` through `transition + 30s`.
- Applies extra transition padding for gradual transitions.
- Merges nearby/overlapping segments.

This is already better than pure full-frame OCR or multi-minute checkpoint scanning. The main remaining issue is that it still repeatedly pulls individual frames by timestamp from `MediaMetadataRetriever`. The PDF specifically warns that frame-level localization should decode locally rather than trust coarse seeks or timestamp grabs.

### Current export behavior

The app exports using `MediaExtractor + MediaMuxer`. It also aligns export segment starts backward to previous video sync samples for safer muxing.

That is good for avoiding broken segment joins, but detection accuracy and export safety are currently not modeled as separate timestamps in the user-facing report.

The app should explicitly separate:

- `eventBoundaryMs`: true visible number-change time.
- `requestedCutStartMs`: `eventBoundaryMs - 10_000`.
- `muxAlignedStartMs`: actual start after sync-sample alignment.

### Current reports

The scan report currently records:

- source URI
- profile label
- frame step
- candidate count
- scan mode
- duration
- transition count
- ROI percentages
- timing buckets

It does not record enough evidence to debug whether a cut is right or wrong. It should include candidate windows, rejected candidates, before/after OCR values, confidence, boundary timestamps, and export-alignment timestamps.

## Findings from the PDF and required next-version additions

### 1. Add a `FrameProvider` abstraction

Current gap:

The scanner calls `MediaMetadataRetriever.getScaledFrameAtTime(...)` and `getFrameAtTime(...)` repeatedly. This is easy to use but expensive and less reliable for exact temporal localization.

Add:

```kotlin
private interface FrameProvider : AutoCloseable {
    suspend fun frameAt(timeMs: Long, targetWidthPx: Int = 0): DecodedFrame?
    suspend fun decodeWindow(
        startMs: Long,
        endMs: Long,
        sampleEveryMs: Long,
        targetWidthPx: Int = 0
    ): List<DecodedFrame>
}

private data class DecodedFrame(
    val timeMs: Long,
    val bitmap: Bitmap
)
```

Implementation detail:

- Add a first implementation wrapping the existing `MediaMetadataRetriever` so behavior remains stable.
- Add the new production implementation using `MediaExtractor + MediaCodec`.
- Use the `MediaCodec` implementation for dense local windows first, then for the full coarse scan once stable.

Why it matters:

- The PDF recommends a windowed decode API, not repeated random timestamp access.
- `MediaCodec` output preserves presentation timestamps.
- Dense local decode is the reliable way to find the first visible changed frame.

Acceptance criteria:

- Scanner can decode a bounded window, for example `12:19.000 -> 12:22.000`, and return ordered frames with timestamps.
- Transition refinement uses `decodeWindow(...)` instead of only binary searching timestamp seeks.
- Existing retriever path remains available as a fallback/debug mode.

### 2. Replace luma-only ROI signatures with rich ROI samples

Current gap:

The current `RoiSignature` stores only 64 luma samples and an average. That catches big visual changes, but it is weak for low-contrast digits, transparent overlays, compression shimmer, and small digit changes.

Add:

```kotlin
private data class RoiSample(
    val timeMs: Long,
    val signature: RoiSignatureV2,
    val visibility: Float,
    val sourceFrameTimeMs: Long
)

private data class RoiSignatureV2(
    val lumaGrid16: IntArray,
    val averageLuma: Int,
    val contrast: Float,
    val edgeEnergy: Float,
    val foregroundRatio: Float,
    val changedPixelRatio: Float? = null,
    val cellScores: FloatArray = floatArrayOf()
)
```

Feature extraction should include:

- 16x16 luma grid instead of 8x8.
- Mean absolute luma difference.
- Edge-energy difference from a simple Sobel-like or neighbor-gradient pass.
- Contrast/range inside the ROI.
- Binarized foreground area using adaptive or local thresholding.
- Optional per-digit cell scores when the ROI is split into fixed digit slots.

Why it matters:

- The PDF recommends cheap visual signals before OCR, including pixel difference, edge energy, thresholded foreground area, and per-cell scores.
- Richer signals reduce unnecessary OCR and catch small digit-only changes better.

Acceptance criteria:

- Candidate generation uses a combined score, not just luma delta.
- Scan reports include per-candidate peak luma delta, edge delta, foreground delta, and visibility.
- Thresholds can be tuned per scan profile.

### 3. Add hysteresis-based candidate detection

Current gap:

The app opens candidate windows when a single signature delta crosses a threshold or a periodic probe is due. That can overreact to one noisy sample or miss a sustained but subtle transition.

Add:

```kotlin
private data class CandidateWindow(
    val startMs: Long,
    val endMs: Long,
    val seedMs: Long,
    val reason: CandidateReason,
    val peakScore: Float,
    val sampleCount: Int
)

private enum class CandidateReason {
    LumaDelta,
    EdgeDelta,
    ForegroundDelta,
    VisibilityChange,
    PeriodicProbe,
    DetectorReacquire
}
```

Candidate rules:

- Require 2 consecutive changed samples before opening a visual candidate.
- Require 2 consecutive stable samples before closing it.
- Merge candidates closer than 1-2 seconds.
- Expand each candidate by 1 second before confirmation.
- Keep periodic probe candidates, but mark them separately so reports show whether a transition came from visual evidence or safety probing.

Why it matters:

- The PDF recommends hysteresis to avoid flicker, compression noise, and transient overlays.

Acceptance criteria:

- Candidate report distinguishes true visual candidates from periodic probes.
- A single noisy frame does not open a candidate unless it is confirmed by another nearby sample.

### 4. Add structured transition marks and OCR evidence

Current gap:

The app returns only final `SegmentWindow` values plus basic timing. That hides why a transition was accepted.

Add:

```kotlin
private data class ConfirmedTransition(
    val fromNumber: Int?,
    val toNumber: Int,
    val lowerBoundMs: Long,
    val upperBoundMs: Long,
    val candidate: CandidateWindow
)

private data class TransitionMark(
    val eventBoundaryMs: Long,
    val fromNumber: Int?,
    val toNumber: Int,
    val confidence: Float,
    val requestedCutStartMs: Long,
    val requestedCutEndMs: Long,
    val muxAlignedStartMs: Long? = null,
    val evidence: List<OcrEvidence>
)

private data class OcrEvidence(
    val timeMs: Long,
    val value: Int?,
    val rawText: String,
    val confidence: Float?,
    val recognizer: String
)
```

Transition acceptance rules:

- Accept `null -> 1`.
- Accept `n -> n + 1` as normal.
- Accept `n -> m` where `m != n`, but mark it as non-sequential.
- Reject candidates where before and after are both null.
- Reject candidates where before and after are the same number unless visual evidence is very strong and later OCR confirms a change.

Why it matters:

- The PDF frames the goal as temporal localization, not just digit recognition.
- The app needs auditable evidence for every cut so false positives and late cuts can be debugged.

Acceptance criteria:

- `ScanFindResult` returns both `segments` and `transitionMarks`.
- Logs and reports show every accepted transition as `from -> to at time`.
- Reports include rejected candidate counts and rejection reasons.

### 5. Replace binary-only refinement with dense local refinement

Current gap:

The current `refineTransitionBoundary(...)` uses binary-search-style timestamp probes. Binary search is only reliable when the interval contains exactly one monotonic state change and every probe returns accurate OCR.

Add:

Dense local refinement should be the default:

```kotlin
private suspend fun refineTransitionBoundaryDense(
    transition: ConfirmedTransition,
    frameProvider: FrameProvider,
    scanWindow: ScanWindow,
    refinementStepMs: Long
): TransitionMark
```

Algorithm:

- Decode from `lowerBoundMs - 1000` to `upperBoundMs + 1000`.
- Sample every 100-250 ms.
- For each sample, compute ROI signature and run recognition only where the visual state appears near the boundary.
- Pick the earliest timestamp where `toNumber` appears.
- Require another `toNumber` sample within 500 ms when available.
- Fall back to visual-only confidence only if OCR is missing but the visual state is stable and clearly changed.

Use binary search only when:

- the bracket is short,
- there is exactly one candidate change,
- before/after OCR is strong,
- and the frame provider can return sufficiently accurate frames.

Why it matters:

- The PDF explicitly says dense local refinement is safer when windows may contain multiple changes.

Acceptance criteria:

- Transition boundary is selected from actual decoded sample timestamps.
- Scan report stores the local refinement samples used to select the mark.
- A candidate window containing two close changes is handled by dense pass or split, not collapsed into one arbitrary binary-search result.

### 6. Add a recognizer layer instead of hardcoding ML Kit OCR

Current gap:

The app currently uses ML Kit text recognition directly and parses the first integer. The PDF discusses OCR tuning, template matching, and custom digit classifiers. ML Kit is convenient, but the app needs a recognizer interface to support faster digit-specific paths.

Add:

```kotlin
private interface DigitRecognizer : AutoCloseable {
    val name: String
    suspend fun recognize(roi: Bitmap): DigitRecognition
}

private data class DigitRecognition(
    val value: Int?,
    val rawText: String,
    val confidence: Float?,
    val perDigit: List<Int?> = emptyList()
)
```

Recognizer implementations:

- `MlKitDigitRecognizer`: current behavior, but return raw text and confidence where available.
- `TemplateDigitRecognizer`: fixed-overlay template matching for known fonts or captured digit slots.
- `HybridDigitRecognizer`: runs template matching first, ML Kit only on uncertainty.
- Future `TfliteDigitRecognizer`: custom digit classifier for stable high-volume domains.

Do not make Tesseract the immediate Android default unless the native dependency and APK size are acceptable. The PDF's Tesseract recommendations still matter conceptually: digit-only recognition, small-region mode, preprocessing, and whitelisting. On Android, those ideas should be implemented through the recognizer interface and preprocessing branches rather than forcing a risky engine swap in the next version.

Acceptance criteria:

- Scanner does not call ML Kit directly from transition logic.
- Recognition evidence records recognizer name.
- Template matching can be enabled for fixed overlays without removing ML Kit fallback.

### 7. Add preprocessing branches

Current gap:

The app mostly crops and scales the ROI. It does not maintain multiple preprocessing branches.

Add these branches:

- `grayScaled`: grayscale, resized ROI.
- `thresholded`: adaptive/local threshold ROI.
- `invertedThreshold`: inverted branch for light-on-dark or dark-on-light overlays.
- `edge`: edge/morphology branch for visual scoring.
- `digitCells`: optional fixed slot crops for multi-digit overlays.

Use:

- visual candidate generation from cheap branches,
- OCR/template recognition from the branch that scores best for visibility,
- report/debug snapshots only when a debug flag is enabled.

Acceptance criteria:

- Low-contrast and transparent overlays can be tested with branch-specific scores.
- The app can identify which preprocessing branch produced the accepted OCR result.

### 8. Add per-transition review UI

Current gap:

The app previews the final compilation, but the user cannot inspect the detected transition list before export/save.

Add:

- A transition review panel after scanning and before final save.
- List rows for each mark:
  - `#4 -> #5`
  - event time
  - requested cut start/end
  - confidence
  - candidate reason
  - warning badge for non-sequential changes
- Tap a row to preview source video around `eventBoundaryMs - 3s` through `eventBoundaryMs + 3s`.
- Actions:
  - enable/disable transition
  - adjust boundary +/- 250 ms
  - adjust pre-roll/post-roll for that transition
  - rebuild preview

Why it matters:

- The PDF recommends measuring and debugging bad cuts. The current app only lets the user accept or discard the whole compilation.

Acceptance criteria:

- User can remove a false positive before saving.
- User can inspect at least 3 seconds before and after each detected boundary.
- User can see whether export alignment moved the mux start earlier than the requested cut.

### 9. Expand scan report schema

Current gap:

Reports are useful for timing but not enough for detection quality analysis.

Add JSON fields:

```json
{
  "scannerVersion": "v2-windowed-roi-change-map",
  "source": {
    "durationMs": 0,
    "width": 0,
    "height": 0,
    "rotation": 0,
    "frameRateEstimate": null,
    "mime": null
  },
  "scanConfig": {
    "coarseStepMs": 500,
    "refinementStepMs": 100,
    "candidateThreshold": 0,
    "minEventSeparationMs": 0,
    "roiPaddingPercent": 0
  },
  "candidateWindows": [],
  "rejectedCandidates": [],
  "transitionMarks": [],
  "timing": {
    "decodeMs": 0,
    "roiFeatureMs": 0,
    "candidateMs": 0,
    "recognitionMs": 0,
    "refinementMs": 0,
    "exportMs": 0
  }
}
```

Acceptance criteria:

- Every accepted segment can be traced back to a candidate and evidence.
- Report includes scan throughput as `videoDurationMs / wallClockMs`.
- Old reports can still be read or ignored safely.

### 10. Add benchmark/test workflow

Current gap:

There is no automated benchmark that proves scanner accuracy or speed across known cases.

Add:

- A small local benchmark manifest under `docs/` or `reports/`.
- A folder convention for test videos and ground-truth JSON.
- Synthetic overlay generator or at least a documented script plan.
- Metrics:
  - event precision
  - event recall
  - F1
  - temporal error in ms
  - false positives per hour
  - missed changes
  - scan wall-clock ratio
  - bad cut rate

Required test cases:

- `null -> 1`.
- `1 -> 2`.
- `4 -> 5`.
- Long static number with no change for several minutes.
- Low-contrast ROI.
- Heavy compression.
- Fade/animated number transition.
- Multi-digit number such as `9 -> 10`.
- Two changes close together.

Acceptance criteria:

- At least one benchmark command or documented manual procedure produces a metrics summary.
- The next version target should be: detected boundary within 500 ms for normal mode and within 250 ms for dense mode on curated test videos.

## Lower-priority additions

These are recommended by the PDF but should not block the next scanner release unless the app's input videos require them immediately.

### Dynamic ROI / Pipeline B

Add this after Pipeline A is solid.

Use case:

- overlay position changes,
- ROI drifts,
- digit visibility is occluded,
- input videos have different layouts.

Feature set:

- periodic text-region detection,
- tracker confidence,
- ROI reacquisition,
- alignment by phase correlation/ECC/optical-flow-style movement,
- fallback to full-frame detector only when fixed ROI confidence drops.

Reason to defer:

- The current app already assumes manual ROI selection.
- Pipeline B is more complex and should be built only after fixed/semi-fixed ROI metrics are trustworthy.

### Hardware acceleration and batch worker architecture

Add after `FrameProvider` is stable.

Feature set:

- one decode worker,
- one ROI preprocessing worker pool,
- one recognition worker,
- bounded queues so full bitmaps are not copied repeatedly,
- foreground service for long scans/exports.

Reason to defer:

- The first performance win should come from eliminating repeated retriever seeks and reducing OCR calls.

### Audio cues

Add only for domains where audio reliably correlates with visible number changes.

Use audio as:

- candidate-window prior,
- confidence boost,
- optional onset-gated refinement.

Do not use audio as the primary detector.

## Important product cleanup discovered during comparison

### Quality and output format settings are currently misleading

The app exposes `Low`, `Medium`, `High`, `MP4`, `WEBM`, and `MOV`, but the current export path uses `MediaMuxer` stream copying. The `ExportQuality` CRF/preset fields and most codec fields are not actually used for re-encoding. `WEBM` is also mapped back to MP4 internally.

Next version should either:

- relabel these as container/copy options and hide unsupported formats, or
- add a real transcoding/exporter path.

For this scanner-focused release, the safer choice is to clarify the UI and report:

- show "Copy original quality" instead of quality presets when mux-copying,
- disable or hide WebM/MOV unless real encoding exists,
- keep MP4 as the reliable default.

## Proposed next-version scope

### Must ship

1. `FrameProvider` interface with retriever fallback and MediaCodec windowed decode for local refinement.
2. `RoiSample` and `RoiSignatureV2` with richer visual features.
3. Hysteresis candidate detection.
4. `TransitionMark` and `OcrEvidence` model.
5. Dense local refinement inside candidate windows.
6. Expanded scan report schema.
7. Transition review list in the UI.
8. Basic benchmark manifest and at least four ground-truth videos/cases.
9. UI cleanup for misleading quality/format controls.

### Should ship if time allows

1. `DigitRecognizer` interface.
2. ML Kit recognizer refactor to return raw text/evidence.
3. Template matching recognizer for fixed overlays.
4. Per-transition boundary adjustment controls.

### Should not ship yet unless required

1. Full dynamic ROI text detector/tracker.
2. Custom TFLite digit classifier.
3. Audio-based candidate generation.
4. Full transcoding pipeline.

## Suggested implementation order

1. Add new data models and report schema while preserving current scanner output.
2. Introduce `FrameProvider` with a retriever-backed adapter.
3. Move existing frame extraction through `FrameProvider`.
4. Add dense local refinement using `FrameProvider.decodeWindow(...)`.
5. Add richer ROI features and candidate hysteresis.
6. Return `TransitionMark` values from the scanner.
7. Write expanded JSON reports.
8. Build the transition review UI.
9. Add benchmark manifest and test cases.
10. Add `MediaCodecFrameProvider` and switch dense refinement to it.
11. Refactor ML Kit behind `DigitRecognizer`.
12. Add template matching as an optional fast recognizer.

## Definition of done for the next version

The next version is complete when:

- The scanner no longer depends solely on repeated `MediaMetadataRetriever` timestamp extraction for exact boundary localization.
- Every accepted cut has a recorded transition mark with before/after evidence.
- The report distinguishes visual candidate detection, OCR confirmation, dense refinement, and export sync alignment.
- The user can inspect each transition before saving.
- The app detects `null -> 1`, `1 -> 2`, and `4 -> 5` in curated videos within the chosen tolerance.
- Normal mode target: boundary within 500 ms.
- Dense mode target: boundary within 250 ms.
- False positives can be removed before final save.
- Quality/format controls no longer imply unsupported transcoding.

