# Compilation Maker Technical Implementation Specification

Status: approved implementation plan; Phase 1 is not yet complete
Last updated: 2026-07-12
Primary fixture: `compilation test video A.mp4`
Reference fixture: `compilation test video B.mp4`

This document is the single source of truth for completing Compilation Maker. It combines the functional roadmap, scanner-validation policy, current diagnosis, implementation sequence, data contracts, algorithms, test requirements, release procedure, and final shipping gates. If another project document conflicts with this specification, this document controls unless Dave explicitly approves a change.

The terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative. A requirement marked MUST is part of the completion gate.

## 1. Product objective

Compilation Maker MUST:

1. Accept one source video and a normalized region of interest (ROI) containing an on-screen sequence number.
2. Detect actual number-state transitions from the source video's decoded frames.
3. Create one source window from exactly 10 seconds before through 30 seconds after each accepted transition, clamped to source bounds.
4. Concatenate the planned windows into an H.264/AAC MP4 with accurate non-keyframe boundaries.
5. Persist the job and output so processing and preview survive Activity recreation, app backgrounding, and process restart.
6. Prove correctness with structured scan evidence and an independent comparison against the reference video.
7. Provide the polished Material 3 workflow described in Phase 2 only after the Phase 1 functional gate passes.

For the primary fixture, the required result is ten 40-second clips and a 400-second compilation matching Video B within the tolerances in section 15.

## 2. Non-negotiable reference-video policy

Video A is scanner input. Video B is a post-generation reference oracle. Video B MUST NOT provide transition timestamps, ROI data, number states, clip windows, or any other input to the application or scanner.

The permitted data flow is:

```text
Video A -> app scanner -> transition evidence -> clip plan -> generated output
generated output + Video B -> host-side QA comparison -> pass/fail report
```

The following data flow is prohibited:

```text
Video A + Video B -> app/scanner -> copied or inferred reference cut plan
```

Enforcement requirements:

- The app's production scan API MUST accept exactly one source URI. It MUST have no reference-video parameter.
- Video B MUST remain on the QA host and MUST NOT be copied to the emulator used by the app. This supersedes earlier wording that proposed staging both fixtures on-device.
- Expected fixture timestamps MUST exist only in host-side QA assertions or test resources. They MUST NOT appear in production source sets.
- A repository check MUST fail if the complete expected schedule, or five or more distinct expected fixture timestamps, occurs in any one file under `app/src/main`. Common isolated constants such as `255` MUST NOT trigger a false positive.
- Unit tests MUST use randomized transition positions through fake frame samplers so fixed fixture times cannot satisfy the tests.
- Integration tests MUST include at least one generated fixture whose transitions differ from Video A.
- A run that uses the legacy scanner or any fallback scanner MUST report the fallback and MUST NOT satisfy the Phase 1 gate.

## 3. Current baseline and confirmed blockers

As of 2026-07-12:

| Area | Current implementation | Required change |
| --- | --- | --- |
| UI and engine | `MainActivity.kt` is approximately 4,955 lines and includes UI, scanning, OCR coordination, export, updates, and lifecycle logic. | Extract interfaces and implementations described in section 6. |
| QA fixture selection | The Windows runner reaches DocumentsUI but does not prove that the picker returned a URI. It later fails with `scan profile picker not found`. | Stub `ACTION_OPEN_DOCUMENT` with Espresso-Intents for deterministic scanner tests. Keep DocumentsUI as a separate smoke test. |
| Hosted emulator | `.github/workflows/release-android-qa.yml` uses `windows-latest`. | Move hosted emulator QA to Ubuntu/KVM. Keep PowerShell only for local Windows QA. |
| Scanner | The current scanner can sample densely or at checkpoints, requires two consecutive visual changes, creates narrow windows around a seed, and has a global OCR budget. | Replace it with the deterministic hybrid interval scanner in section 8. |
| OCR | Bundled ML Kit and callback propagation exist, but timeout retry and bitmap ownership are not fully proven. | Implement section 9 and its tests. |
| Export | `MediaExtractor`/`MediaMuxer` seeks to the previous sync sample. A requested 65-second start can become 64 seconds. | Replace compilation export with Media3 Transformer composition. |
| Verification | Checks file readability, size, duration, and a video track. | Add audio, decode, timeline, transition, duration, and A/B verification. |
| Instrumentation | One instrumentation manifest test exists. | Add deterministic picker, Worker, OCR, lifecycle, export, and end-to-end tests. |
| Build tooling | AGP 8.5.1, Gradle 8.8, JDK 17, compile/target API 35. | Upgrade to AGP 8.13.2 and Gradle 8.13; retain JDK 17. |
| Signing | Keystore and passwords are committed. | Rotate immediately and load signing material from local environment/GitHub secrets. |

The latest QA result is **BLOCKED before scanning**. It is not evidence that transition detection succeeds or fails. No claim of functional completion is permitted until the complete A-to-output-to-B comparison runs.

## 4. Delivery order and dependency rules

Implementation MUST occur in this order:

1. Increment 1.0: trustworthy QA, build tooling, and signing.
2. Increment 1.1: engine extraction and stable contracts.
3. Increment 1.2: deterministic OCR.
4. Increment 1.3: correct interval detection and localization.
5. Increment 1.4: accurate export and complete verification.
6. Increment 1.5+: evidence-driven repairs until the Phase 1 gate passes twice.
7. Increment 2.0: Compose/UDF architecture.
8. Increment 2.1: guided creation workflow.
9. Increment 2.2: accessibility, visual polish, and performance.
10. Increment 2.3: final shipping gate.

Rules:

- Do not start Phase 2 while Phase 1 is red.
- Within Phase 1, fix the earliest causal failure in the current QA report before later failures.
- Preserve working WorkManager identity, unique-work `KEEP`, foreground execution, cancellation, ROI persistence, lifecycle restoration, and finalization logging during extraction.
- Refactoring MUST be behavior-preserving unless the increment explicitly changes that behavior.
- Every behavior change MUST add a regression test in the same patch.

## 5. Required project layout

Keep package root `com.example.compilationmaker`. Create the following production structure:

```text
app/src/main/java/com/example/compilationmaker/
  app/
    CompilationMakerApp.kt
  ui/
    MainActivity.kt
    navigation/
    home/
    roi/
    review/
    processing/
    result/
    settings/
  work/
    CompilationWorker.kt
    CompilationJobStore.kt
    CompilationWorkScheduler.kt
  domain/
    model/
      ScanModels.kt
      CompilationModels.kt
      QaModels.kt
    FrameSampler.kt
    DigitRecognizer.kt
    TransitionDetector.kt
    ClipPlanner.kt
    CompilationExporter.kt
    OutputVerifier.kt
  scanner/
    RetrieverFrameSampler.kt
    RoiNormalizer.kt
    EdgeSignatureCalculator.kt
    HybridIntervalTransitionDetector.kt
    MlKitDigitRecognizer.kt
    StableNumberClassifier.kt
  export/
    Media3CompilationExporter.kt
  verification/
    AndroidOutputVerifier.kt
  qa/
    QaReportWriter.kt
```

Test structure:

```text
app/src/test/.../scanner/
app/src/test/.../domain/
app/src/test/.../work/
app/src/androidTest/.../picker/
app/src/androidTest/.../ocr/
app/src/androidTest/.../worker/
app/src/androidTest/.../e2e/
tools/android-qa/
tools/fixtures/
```

Do not move updater code during Phase 1 unless required to make `MainActivity` compile after extraction.

## 6. Required interfaces and ownership contracts

The names may change only if all responsibilities and tests remain one-to-one. Production orchestration MUST depend on these interfaces, not concrete implementations.

```kotlin
interface FrameSampler : AutoCloseable {
    suspend fun metadata(): SourceVideoMetadata
    suspend fun frameAt(timeMs: Long, targetWidthPx: Int): FrameReadResult
    suspend fun sampleRange(
        startMs: Long,
        endMs: Long,
        stepMs: Long,
        targetWidthPx: Int,
        consume: suspend (RoiFrame) -> SampleDecision
    )
}

interface DigitRecognizer : AutoCloseable {
    suspend fun recognize(frame: RoiFrame): DigitRecognitionResult
}

interface TransitionDetector {
    suspend fun detect(request: TransitionDetectionRequest): TransitionDetectionResult
}

interface ClipPlanner {
    fun plan(durationMs: Long, transitions: List<TransitionMark>): ClipPlan
}

interface CompilationExporter {
    suspend fun export(request: ExportRequest): ExportResult
    suspend fun cancel()
}

interface OutputVerifier {
    suspend fun verify(request: OutputVerificationRequest): OutputVerificationResult
}
```

Supporting result contracts:

```kotlin
sealed interface FrameReadResult {
    data class Success(val frame: RoiFrame) : FrameReadResult
    data class InvalidFrame(val requestedTimeMs: Long, val reason: String) : FrameReadResult
    data class Failure(val requestedTimeMs: Long, val cause: Throwable) : FrameReadResult
}

enum class SampleDecision { CONTINUE, STOP }

data class OcrEvidence(
    val requestedTimeMs: Long,
    val actualTimeMs: Long?,
    val parsedNumber: Int?,
    val rawText: String,
    val status: OcrStatus,
    val attempt: Int,
    val elapsedMs: Long,
    val boundingBox: IntRect?
)

data class TransitionDetectionRequest(
    val sourceUri: Uri,
    val roi: ScanWindow,
    val expectedRotationDegrees: Int,
    val profile: ScanProfile
)

data class TransitionDetectionResult(
    val transitions: List<TransitionMark>,
    val candidates: List<CandidateInterval>,
    val metrics: ScanMetrics,
    val terminalError: ScanFailure?
)
```

`ExportRequest`, `ExportResult`, `OutputVerificationRequest`, and `OutputVerificationResult` MUST carry typed values rather than unstructured maps. At minimum they carry source/output URIs, clip plan, media metadata, progress, cancellation outcome, track/decode evidence, and failure cause. No production interface may return `Any`, raw JSON, or a nullable Boolean as its result.

Ownership rules:

- `RoiFrame` MUST implement `AutoCloseable` and own exactly one live bitmap.
- The caller that receives a successful `FrameReadResult` owns the frame and MUST close it.
- `DigitRecognizer.recognize` borrows the frame; it does not take ownership.
- The caller MUST keep the frame alive until the ML Kit `Task` reaches success, failure, or cancellation. A coroutine timeout does not prove that the underlying Task has terminated.
- No component may recycle a bitmap that another active Task references.
- Every sampler, recognizer, decoder, extractor, transformer, file stream, and retriever MUST close in `finally` or through `use`.

## 7. Domain models and terminal semantics

Implement these models as immutable data classes or sealed results:

```kotlin
data class SourceVideoMetadata(
    val durationMs: Long,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotationDegrees: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean
)

data class ScanWindow(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float
)

data class CandidateInterval(
    val startMs: Long,
    val endMs: Long,
    val leftCheckpointMs: Long,
    val rightCheckpointMs: Long,
    val peakChangeScore: Float,
    val reason: CandidateReason
)

enum class CandidateConfirmation {
    CONFIRMED_TRANSITION,
    NO_TRANSITION,
    OCR_UNAVAILABLE,
    OCR_TIMEOUT,
    INVALID_FRAME
}

data class TransitionMark(
    val eventBoundaryMs: Long,
    val fromNumber: Int?,
    val toNumber: Int,
    val confidence: Float,
    val confirmation: CandidateConfirmation,
    val evidence: List<OcrEvidence>
)

data class PlannedClip(
    val startMs: Long,
    val endMs: Long,
    val transitionIds: List<Int>
)

data class ClipPlan(
    val requestedClips: List<PlannedClip>,
    val mergedClips: List<PlannedClip>,
    val totalDurationMs: Long
)

enum class CompilationOutcome {
    SUCCEEDED,
    NO_RESULTS,
    FAILED,
    CANCELLED
}
```

Terminal rules:

- `SUCCEEDED`: export and all in-app verification checks passed.
- `NO_RESULTS`: scan completed without any confirmed transition. This is not a crash and MUST show a specific user message.
- `FAILED`: a non-cancellation failure prevented a valid output.
- `CANCELLED`: user, WorkManager, or system cancellation was observed. Partial output MUST be deleted.
- OCR failure for one candidate MUST NOT fail the whole scan. It records a candidate result and scanning continues.
- A fallback scanner MUST set `fallbackUsed=true`; such a run cannot pass fixture QA.

## 8. Transition scanner specification

### 8.1 Scanner identity

The production scanner introduced by Increment 1.3 MUST report:

```text
scannerVersion = "v2-hybrid-interval-edge-ocr"
```

Any other scanner version fails the Phase 1 fixture gate.

### 8.2 Preflight

Before scanning:

1. Open the source URI and read metadata.
2. Reject a source when duration is non-positive, no video track exists, dimensions are invalid, or rotation is not one of `0`, `90`, `180`, `270`.
3. Clamp ROI fractions to `[0,1]` and reject width or height below `0.01` after clamping.
4. Decode one frame at timestamp zero using the same frame path used by coarse scanning.
5. Rotate into display orientation, crop the ROI, and verify the crop is at least `32 x 32` pixels after scaling.
6. Run one bundled-ML-Kit preflight recognition. A valid empty result is acceptable; model/initialization failure is not.
7. Record metadata, ROI, frame dimensions, decode time, and OCR preflight result.

### 8.3 Coarse checkpoint plan

The normal profile MUST use a 60,000 ms coarse interval. Generate timestamps as follows:

```kotlin
val checkpoints = buildList {
    var t = 0L
    while (t < durationMs) {
        add(t)
        t += 60_000L
    }
    if (lastOrNull() != durationMs) add(durationMs)
}
```

Requirements:

- Timestamp zero is the baseline only and MUST NOT emit a transition by itself.
- On API 27 and later, every timestamp MUST be fetched directly with `MediaMetadataRetriever.getScaledFrameAtTime(..., OPTION_CLOSEST, ...)` or an equivalent direct-seek sampler. On API 24–26, use `getFrameAtTime(..., OPTION_CLOSEST)` followed by immediate ROI crop/downscale. Coarse scanning MUST NOT decode intervening frames on either path.
- The primary fixture MUST produce exactly 61 coarse retrieval attempts.
- Each frame MUST be rotated, cropped to ROI, and downscaled immediately; full-frame bitmaps MUST be released before requesting the next checkpoint.
- A missing coarse frame records `INVALID_FRAME` evidence for that timestamp and does not abort remaining checkpoints.

### 8.4 ROI signature

Do not compare raw full-frame luminance. For each cropped ROI:

1. Resize to `96 x 96` grayscale.
2. Compute local normalization with a `9 x 9` mean/variance window and epsilon `1e-3`.
3. Compute Sobel horizontal and vertical gradients.
4. Convert gradient magnitude into a `6 x 6` cell histogram by averaging each `16 x 16` cell.
5. L2-normalize the resulting 36-element vector.
6. Store mean normalized contrast and text-like edge occupancy as secondary features.

Define adjacent-state difference on a `0..100` scale:

```text
score = 70 * mean(abs(edgeVectorA - edgeVectorB))
      + 20 * abs(edgeOccupancyA - edgeOccupancyB)
      + 10 * abs(normalizedContrastA - normalizedContrastB)
```

Calculate all valid adjacent scores first. Determine the run threshold as:

```text
threshold = clamp(median(scores) + 6 * MAD(scores), 8.0, 25.0)
```

`MAD` is median absolute deviation. Persist the median, MAD, threshold, and every adjacent score.

### 8.5 Candidate creation

For every adjacent valid checkpoint pair `(previous, current)`:

- Create a candidate when its score is greater than or equal to the run threshold.
- The candidate MUST span the complete interval `[previous.timeMs, current.timeMs]`.
- Do not center a `+/-2.5s` window on the later checkpoint.
- Do not require two consecutive changed checkpoint pairs.
- Do not discard the first significant pair as noise. Noise suppression occurs during confirmation.
- Adjacent candidate intervals MAY be merged only when they share a boundary. The merged record MUST retain both original intervals and scores.

### 8.6 Interval investigation

For each candidate interval:

1. Sample the left endpoint, midpoint, and right endpoint.
2. Classify stable number state around each probe using section 9.5.
3. If left and right are stable, equal, and no interior edge peak exceeds threshold, return `NO_TRANSITION`.
4. If left and right differ, bracket a transition between the latest stable old-state frame and earliest stable new-state frame.
5. If midpoint introduces a third state or evidence indicates multiple peaks, split into left and right subintervals and recurse.
6. Stop recursive splitting at depth 8 or interval width `<= 2,000 ms`; then use dense refinement.
7. Continue after `OCR_TIMEOUT`, `OCR_UNAVAILABLE`, or `INVALID_FRAME`; record that result for the affected subinterval.

### 8.7 Dense local change map

Within unresolved or transition-containing intervals:

- Decode only that interval at 500 ms spacing.
- Compute the same ROI signatures.
- Select local peaks at or above the run threshold.
- Cluster peaks whose timestamps are within 1,000 ms.
- For each cluster, use the highest-scoring timestamp as the initial boundary seed.
- Refine around each seed at 250 ms spacing from `seed - 1,500 ms` through `seed + 1,500 ms`, clamped to the candidate interval.

Dense local decoding MAY use sequential `MediaExtractor`/`MediaCodec`. If it falls back to direct `OPTION_CLOSEST` retrieval, the report MUST identify the provider and fallback reason. `OPTION_CLOSEST_SYNC` MUST NOT be used for boundary refinement.

### 8.8 OCR-assisted boundary refinement

After stable before/after states are established:

1. Set `low` to the latest frame classified as `fromNumber`.
2. Set `high` to the earliest frame classified as `toNumber`.
3. Binary-search using actual decoded presentation timestamps until `high - low <= 250 ms` or eight iterations complete.
4. Around `high`, sample at `high`, `high + 250 ms`, and `high + 500 ms`.
5. Set `eventBoundaryMs` to the earliest sample classified as `toNumber` when at least one following sample also classifies as `toNumber`.
6. Never substitute the checkpoint time, seed time, requested decode time, or export keyframe for `eventBoundaryMs`.

Acceptance rules:

- Accept `null -> 1`.
- Accept `N -> N+1`.
- Record `N -> M` where `M != N+1` as `CONFIRMED_TRANSITION` plus `nonSequential=true` for general operation, but fail the primary fixture gate.
- Reject equal before/after states.
- Reject candidates with no stable state on either side.
- Deduplicate two marks only when they have the same `fromNumber`, same `toNumber`, and boundaries within 1,000 ms; retain the earlier boundary and combine evidence.

### 8.9 Performance gates

On the API 35 reference emulator for Video A:

- Exactly 61 coarse retrieval attempts.
- No intervening-frame traversal during coarse scanning.
- Average coarse checkpoint wall time `<= 2,000 ms`.
- Total coarse pass wall time `<= 90,000 ms`.
- No OCR call may wait silently beyond its configured timeout.
- Dense decoding is limited to candidate intervals and MUST report total decoded video milliseconds.

## 9. OCR specification

### 9.1 Dependency and lifecycle

- Retain only bundled `com.google.mlkit:text-recognition:16.0.1` during Phase 1.
- Construct one `TextRecognizer` per `CompilationWorker` execution.
- Serialize all recognizer calls with a `Mutex`.
- Close the active recognizer exactly once at Worker completion or replacement.
- Log complete `MlKitException` code, message, and cause without logging source-media contents.

### 9.2 Task awaiter

Implement one generic Google Task awaiter using `suspendCancellableCoroutine`. It MUST register success, failure, and cancellation listeners before suspension and resume exactly once through an atomic guard.

Required tests:

- Success returns the result.
- Failure throws the original cause.
- Task cancellation cancels the continuation.
- Coroutine cancellation does not double-resume when the Task later completes.
- Listener races cannot resume twice.

### 9.3 Timeout and retry

Constants:

```text
OCR_CALL_TIMEOUT_MS = 5,000
OCR_MAX_ATTEMPTS = 2
OCR_CANDIDATE_TIMEOUT_MS = 20,000
```

On first-attempt timeout:

1. Mark the original Task abandoned for result delivery, but retain its bitmap until the Task terminates.
2. Close the original recognizer.
3. Copy the ROI to a validated, immutable `ARGB_8888` bitmap of at least `32 x 32` pixels.
4. Create one replacement recognizer.
5. Retry exactly once with the copy.
6. Close/recycle each bitmap only after its corresponding Task terminates.

If the retry times out, return `OCR_TIMEOUT`. Do not throw a scan-wide timeout. Remove the existing global 35-second confirmation budget.

### 9.4 Digit parser

Normalize OCR text by trimming surrounding whitespace only. Accept a value only when one complete text line matches:

```regex
^[0-9]{1,3}$
```

Rules:

- Reject mixed letters, punctuation, decimals, signs, and numbers longer than three digits.
- Accept values `0..999` at the parser layer.
- Sequence expectations belong to the transition validator, not the parser.
- Preserve raw text, bounding box, recognized value, branch, elapsed time, and error status in evidence.

### 9.5 Stable-state classification

For a probe time `t`, recognize frames at offsets:

```text
-1000 ms, -500 ms, 0 ms, +500 ms, +1000 ms
```

Clamp offsets to source bounds and collapse duplicate timestamps. A number state is stable when:

- At least three samples return the same number; and
- No competing number has two or more votes.

A null state is stable when at least three valid OCR calls return no plausible digit and no number has two votes. Timeout, unavailable-model, and invalid-frame results are not null votes. If neither rule passes, return `UNSTABLE`.

### 9.6 Confidence-preserving `6/9` adjudication

The remaining `6 -> 7`, `8 -> 9`, and `9 -> 10` false negatives have complete visual-candidate coverage but unstable or false checkpoint states because ML Kit alternates between `6` and `9`. The recognizer MUST retain ML Kit's real line, element, and symbol confidence plus bounds/corner points, rotation, branch, status, and elapsed time. It MUST NOT assign `0.95` merely because text is a short integer.

Before a sample becomes a `StableStateVote`, invoke a pure JVM-testable `classifySixOrNine(luma, width, height)` only when the complete ML Kit integer is exactly `6` or `9`. The classifier MUST:

- evaluate Otsu and average-threshold bright-glyph masks;
- select a plausible connected foreground component and reject tiny, border-dominated, or nearly full-ROI components;
- flood-fill component-box background from its perimeter and measure enclosed holes;
- accept exactly one plausible dominant hole;
- classify `NINE` for normalized hole-centroid Y `<= 0.42`, `SIX` for Y `>= 0.58`, and otherwise `AMBIGUOUS`;
- require agreement between structurally valid threshold variants; disagreement is `AMBIGUOUS`;
- return structured component bounds, threshold/polarity, hole count/area/centroid, confidence, and reason.

A high-confidence topology result MAY override only ML Kit integer `6` or `9`. It MUST NOT alter `10`, `8`, `0`, other digits, or arbitrary text containing a `6`/`9`. Ambiguous topology preserves the raw ML Kit reading as evidence but is not a number vote, stable null, or `NO_TRANSITION`. Sequence rules validate recognized values after adjudication and MUST NOT force an expected number.

### 9.7 Vote and report evidence

Every checkpoint vote MUST retain requested and actual decoded timestamps, raw ML Kit value/text/confidence/status, line/element/symbol structure, preprocessing branch, topology decision/metrics, final adjudicated value/status, and decode/OCR timing. Five-sample voting remains exactly `-1000,-500,0,+500,+1000 ms`; a number still requires at least three votes with no competitor receiving two, and a `3-2` split remains unstable.

### 9.8 Unstable-run bridging

Topology correction is evaluated before interval-policy changes. If sequential intervals remain unresolved, contiguous visual-opened intervals sharing unstable checkpoints MUST be bridged from the nearest preceding stable checkpoint through the nearest following stable checkpoint. The complete envelope and every original interval/peak remain evidence. Probe allocation MUST be deterministic, with at least three probes, two additional probes per retained visual peak, and a cap of fifteen. At `<=2,000 ms`, sample at 500 ms and refine at 250 ms to the earliest persistent new stable state. Visual evidence opens an envelope but never creates a clip.

### 9.9 Separate candidate-timeout causal axis and gates

The fixed 16-second candidate wall deadline is independently known to discard valid work when the Activity is closed. Replace it separately with a bounded OCR-work/progress-aware budget while preserving candidate-local continuation and earlier confirmations. Benchmark and release evidence MUST distinguish timeout-policy effects from topology effects.

Required deterministic coverage includes synthetic/noisy/transformed `6`, `9`, `0`, `7`, `8`, and `10`; broken/open loops, no/multiple/central holes, threshold disagreement, non-`6/9` regression, corrected five-sample voting, ambiguity-versus-null/timeout, actual timestamp retention, complete intervals, unstable-run envelopes, multiple transitions, candidate-local failure continuation, sequential rejection, and persistent-boundary accuracy. Release acceptance requires the exact published APK with Activity closed, foreground service evidence, complete candidate coverage, zero semantic false positives, recall above the equivalent background baseline, no regression below the best six-transition semantic baseline without a documented causal explanation, machine-readable benchmark JSON, and a pre/post comparison report.

## 10. Clip planning

For each accepted `TransitionMark`:

```text
requestedStartMs = max(0, eventBoundaryMs - 10,000)
requestedEndMs   = min(sourceDurationMs, eventBoundaryMs + 30,000)
```

Planner rules:

- Sort marks by `eventBoundaryMs`.
- Apply the 1,000 ms semantic deduplication rule before planning.
- Preserve exact requested boundaries; do not keyframe-align them.
- Merge two planned clips only if their requested intervals overlap or touch. Do not merge across an arbitrary gap.
- A merged clip MUST retain the IDs of all transitions it contains.
- For the primary fixture, no merge is expected: ten marks MUST produce ten 40,000 ms clips.
- Store the unmerged and merged plans in `QaRunReport`.

## 11. Export specification

### 11.1 Dependency

Pin all Media3 artifacts to `1.10.1`:

```kotlin
implementation("androidx.media3:media3-transformer:1.10.1")
implementation("androidx.media3:media3-common:1.10.1")
implementation("androidx.media3:media3-exoplayer:1.10.1")
implementation("androidx.media3:media3-ui:1.10.1")
```

Do not use dynamic versions.

### 11.2 Composition

For every merged `PlannedClip`:

1. Build a `MediaItem` using the source URI.
2. Set clipping start/end from the exact requested milliseconds.
3. Wrap it in an `EditedMediaItem` containing audio and video.
4. Place all edited items, in chronological order, into one `EditedMediaItemSequence`.
5. Build one `Composition`.
6. Set audio and video transmuxing to `false` for the multi-item fixture.
7. Request H.264 video, AAC audio, and MP4 output.
8. Do not enable MP4 edit-list-only trimming.

The exporter MUST transcode boundaries so a requested 65,000 ms start remains 65,000 ms within codec/frame tolerance. It MUST NOT expand the clip to the previous source sync frame.

### 11.3 Threading, progress, and cancellation

- Access each `Transformer` instance from one application thread.
- Convert Transformer callbacks into a cancellable suspend result.
- Poll export progress at no more than 4 Hz.
- Persist progress after a change of at least 1 percentage point or 1 second.
- On Worker cancellation, call Transformer cancellation, await terminal callback where possible, release resources, and delete the partial file.
- Move a completed file to its durable destination only after in-app verification succeeds.

## 12. Worker orchestration and persistence

`CompilationWorker` owns this exact state sequence:

```text
PREPARING
PREFLIGHT
COARSE_SCAN
INTERVAL_REFINEMENT
OCR_CONFIRMATION
BUILDING_CLIP_PLAN
EXPORTING
VERIFYING
FINALIZING
SUCCEEDED | NO_RESULTS | FAILED | CANCELLED
```

Requirements:

- Enqueue unique work using the existing stable unique-work name and `ExistingWorkPolicy.KEEP`.
- Persist the WorkManager UUID immediately after enqueue.
- `CompilationJobStore` is the durable source of job state. Add `schemaVersion` and migration-safe defaults.
- Persist source URI, URI permission state, ROI, scanner profile, current stage, percent, message, candidate count, confirmed count, clip count, output URI/path, report path, timestamps, and terminal error.
- On app start, reconcile persisted state with WorkManager before rendering.
- Do not infer success from a missing WorkInfo record; validate the output and persisted terminal state.
- Do not enqueue duplicate work after Activity recreation.
- Foreground notification creation and updates MUST continue to pass existing tests.

## 13. QA report contract

Each run MUST produce UTF-8 JSON named `qa-run.json` with `schemaVersion = 1`. The minimum structure is:

```json
{
  "schemaVersion": 1,
  "runId": "uuid",
  "startedAtUtc": "ISO-8601",
  "finishedAtUtc": "ISO-8601",
  "terminalOutcome": "SUCCEEDED|NO_RESULTS|FAILED|CANCELLED",
  "firstFailure": null,
  "fixture": {
    "videoASha256": "hex",
    "videoBSha256": "hex-or-host-only",
    "videoADurationMs": 0
  },
  "apk": {
    "versionCode": 0,
    "versionName": "string",
    "gitCommit": "sha",
    "releaseTag": "vX.Y.Z",
    "sha256": "hex",
    "signingCertificateSha256": "hex"
  },
  "scanner": {
    "version": "v2-hybrid-interval-edge-ocr",
    "fallbackUsed": false,
    "frameProvider": "string",
    "fallbackReason": null,
    "roi": {},
    "coarseIntervalMs": 60000,
    "coarseAttempts": 61,
    "coarseSuccesses": 61,
    "coarseWallMs": 0,
    "thresholdMedian": 0.0,
    "thresholdMad": 0.0,
    "changeThreshold": 0.0,
    "checkpoints": [],
    "candidates": [],
    "ocrCalls": [],
    "confirmedTransitions": []
  },
  "clipPlan": {
    "requested": [],
    "merged": [],
    "totalDurationMs": 0
  },
  "output": {
    "uri": "string",
    "path": "string",
    "sha256": "hex",
    "sizeBytes": 0,
    "durationMs": 0,
    "video": {},
    "audio": {},
    "decodeChecks": []
  },
  "comparison": {
    "referenceSha256": "hex",
    "meanSsim": 0.0,
    "p05Ssim": 0.0,
    "failedSamples": [],
    "audioCorrelation": 0.0,
    "passed": false
  }
}
```

The app writes scanner, clip-plan, output, and terminal fields. Host QA appends APK, reference, and comparison fields without rewriting app evidence. A missing required field fails QA.

## 14. Trustworthy QA implementation

### 14.1 QA-note rule

Increment 1.0 MUST add to `AGENTS.md`:

- Read `docs/most-recent-android-qa.md` before every app change.
- After testing an update, replace that note with the latest version, commit, APK hash, fixture hashes, observed evidence, result, and first unresolved causal failure.

### 14.2 Deterministic picker instrumentation

Add Espresso-Intents dependencies and a release-targeted instrumentation suite.

Test procedure:

1. Host verifies Video A against a committed SHA-256 fixture manifest.
2. Host pushes Video A to emulator `MediaStore`; Video B remains host-side.
3. Test resolves Video A's `content://media/...` URI and verifies its hash through `ContentResolver`.
4. Initialize Espresso-Intents before launching the picker action.
5. Stub `ACTION_OPEN_DOCUMENT` with `Activity.RESULT_OK`, the Video A URI, and `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
6. Click the app's select-video control.
7. Assert that the app displays the selected source, persists URI permission when supported, and restores the expected ROI.
8. Start compilation and wait on WorkManager/job-store state, not arbitrary sleeps or screen text.
9. Query the durable output URI, copy the MP4 and report to a pullable QA directory, and assert hashes after `adb pull`.

Keep one separate DocumentsUI smoke test. A DocumentsUI failure may fail picker compatibility QA, but it MUST NOT block scanner tests.

### 14.3 Exact release APK testing

- Build a release-targeted test APK signed with the same rotated certificate as the release APK.
- Download the APK asset from the GitHub release URL.
- Verify downloaded APK SHA-256 and signing certificate against release metadata.
- Install the downloaded release APK, then the matching instrumentation APK.
- Run instrumentation against the installed release package without rebuilding or replacing it.
- Record both APK hashes in `qa-run.json`.

### 14.4 Hosted workflow

Replace the Windows hosted job with:

- `runs-on: ubuntu-latest`
- JDK 17
- KVM enabled
- API 35 Google APIs x86_64 image
- `reactivecircus/android-emulator-runner`
- shell-neutral Gradle/instrumentation commands
- host-side `ffmpeg`, `ffprobe`, JSON validator, and comparison scripts

Keep `tools/android-qa/run-release-qa.ps1` for local Windows execution, but move shared assertions into Kotlin tests or platform-neutral scripts.

Create these platform-neutral host tools:

```text
tools/android-qa/validate_qa_report.py
tools/android-qa/compare_video.py
tools/android-qa/compare_audio.py
tools/android-qa/check_fixture_leaks.py
tools/fixtures/generate_fixture.py
```

Each script MUST return exit code zero only on pass, nonzero on failed assertions or malformed input, print a one-line result to stdout, and write detailed JSON to the requested artifact path. PowerShell and GitHub Actions MUST call these same scripts instead of reimplementing thresholds.

## 15. Primary Video A/B acceptance gate

### 15.1 Source transitions

The generated report MUST contain exactly these ten accepted source boundaries, each within `+/-1,000 ms`:

| Expected source boundary (s) | Expected output boundary (s) |
| ---: | ---: |
| 30 | 10 |
| 75 | 50 |
| 255 | 90 |
| 855 | 130 |
| 975 | 170 |
| 1275 | 210 |
| 1395 | 250 |
| 1995 | 290 |
| 2415 | 330 |
| 3560 | 370 |

No unexpected transition or clip is allowed. A non-sequential transition fails this fixture.

### 15.2 Clip and container checks

- Exactly ten requested clips and ten exported clips.
- Each requested clip duration is `40,000 ms`, except source-bound clamping if ever applicable; no clamping applies to this fixture.
- Total output duration is `400,000 ms +/- 2,000 ms`.
- Readable MP4 container.
- At least one H.264 video track and one AAC audio track.
- Video and audio durations differ by no more than 500 ms.
- Full decode succeeds without fatal errors.
- Explicit decode probes at 5%, 50%, and 95% succeed.
- Output URI remains readable and Media3 Player can prepare it after app process restart.

### 15.3 Visual comparison

Host-side comparison MUST:

1. Normalize output and Video B to Video B's display resolution.
2. Normalize both to 2 frames per second, square pixels, `yuv420p`, and timestamps starting at zero.
3. Compare corresponding full frames with SSIM.
4. Define a boundary-exclusion zone as `+/-1.0s` around each expected output boundary.
5. Define a failed sample as SSIM `< 0.90`.

Required results:

- Mean sampled SSIM `>= 0.95`.
- Fifth-percentile sampled SSIM `>= 0.90`.
- Zero failed samples outside boundary-exclusion zones.

### 15.4 Audio comparison

Host QA MUST decode both audio tracks to mono 16 kHz signed PCM, compensate for an offset of at most `+/-100 ms`, and calculate normalized cross-correlation over each 40-second clip.

- Every clip correlation MUST be `>= 0.90`.
- Mean clip correlation MUST be `>= 0.95`.
- A missing, silent, truncated, or undecodable audio track fails the run.

### 15.5 Consecutive-pass rule

Phase 1 passes only after:

- Two consecutive clean-AVD runs pass every gate.
- One hosted Ubuntu run and one local Windows emulator run agree on transitions, clip plan, duration, and pass/fail.
- A physical-device smoke test passes when a device is available. If no device is connected, report that explicitly; do not claim the physical-device gate passed.
- `docs/most-recent-android-qa.md` says `PHASE 1 PASSED`.

## 16. Anti-hardcoding and generalized-scanner tests

Create deterministic synthetic fixtures under `tools/fixtures` using a committed generator and manifest. The generator MUST accept duration, ROI, background-motion seed, and transition schedule and MUST emit both source and expected compilation.

Required cases:

| Case | Required behavior |
| --- | --- |
| `null -> 1` | Detect first appearance near its actual frame. |
| Sequential `1 -> 2 -> 3` | Detect each transition independently. |
| Shifted schedule | Follow shifted timestamps; known Video A timestamps must fail. |
| Two changes inside one 60s interval | Recursive interval split finds both. |
| Change immediately after checkpoint | Localize near the actual boundary, not the next checkpoint. |
| Long static state | Emit no candidates after confirmation. |
| Moving rainbow background | Normalized edge signature ignores background-only motion. |
| Low contrast | OCR-assisted refinement remains within tolerance or reports explicit uncertainty. |
| Compression artifacts | No false transition from one isolated noisy frame. |
| Fade/animation | Earliest stable new state is selected. |
| Non-sequential number | Record diagnostic; primary fixture policy rejects it. |
| OCR timeout on one candidate | Continue and evaluate later candidates. |
| All OCR unavailable | Return `NO_RESULTS` or `FAILED` per preflight state, never false success. |

Unit tests MUST generate randomized schedules from a fixed test seed and assert output marks derive from fake evidence. Production source MUST contain no fixture-specific timestamp table.

## 17. Build and signing migration

### 17.1 Toolchain

Pin:

```text
Android Gradle Plugin: 8.13.2
Gradle wrapper: 8.13
JDK: 17
compileSdk: 35
targetSdk: 35
minSdk: 24
```

Run a clean build immediately after upgrading before changing scanner behavior.

### 17.2 Signing

1. Generate a new production keystore outside the repository.
2. Remove `keystores/compilationmaker-update.keystore` and all hardcoded passwords from tracked files.
3. Add the keystore path/base64, store password, key alias, and key password to local environment and GitHub secrets.
4. Configure Gradle to fail release builds when required signing values are absent. Do not fall back to debug signing.
5. Publish the new signing-certificate SHA-256 in release metadata and `qa-run.json`.
6. Release notes and update feed MUST state that installations signed by the old key require uninstall/reinstall once.
7. All releases after rotation MUST use the new identity.

No private key or credential may appear in Git history additions, logs, artifacts other than the signed APK, or QA JSON.

## 18. Increment-level completion criteria

### Increment 1.0 — Trustworthy QA

Complete when:

- QA-note rule is in `AGENTS.md`.
- Deterministic picker test reaches the Worker.
- Video B remains host-side.
- Ubuntu/KVM workflow runs.
- Exact release APK and test APK hashes are recorded.
- AGP/Gradle upgrade passes clean build/tests.
- New signing identity is active and old tracked material is removed.

### Increment 1.1 — Testable core

Complete when:

- Interfaces and models from sections 6–7 exist outside `MainActivity`.
- Worker orchestrates interfaces.
- Fake implementations can run a complete in-memory scan/plan/export/verify flow.
- Existing lifecycle, foreground, and unique-work tests remain green.

### Increment 1.2 — Deterministic OCR

Complete when all Task, timeout, retry, lifetime, serialization, parser, majority, and exactly-once-close tests pass on JVM or instrumentation as appropriate.

### Increment 1.3 — Correct scanner

Complete when:

- Scanner version is `v2-hybrid-interval-edge-ocr`.
- Coarse and dense algorithms match section 8.
- Synthetic anti-hardcoding fixtures pass.
- Video A performance gates pass.
- Video A reports ten confirmed transitions within tolerance.

### Increment 1.4 — Exact export and comparison

Complete when:

- Media3 exporter replaces manual sync-expanded muxing for production.
- Exact clip, container, audio, decode, lifecycle, and A/B gates pass.
- Output is 400 seconds within tolerance.

### Increment 1.5+ — Repair loop

After each public Phase 1 patch:

1. Download the exact release APK.
2. Run clean unit, instrumentation, Worker, and Video A/B tests.
3. Update the QA note and machine-readable report.
4. Identify the first causal failure.
5. Implement one scoped fix plus regression test.
6. Publish the next patch only after required build/tests pass.
7. Repeat until two consecutive complete passes.

## 19. Phase 2 UI implementation

Phase 2 starts only after `PHASE 1 PASSED`.

### Increment 2.0 — Compose architecture

- Migrate to Jetpack Compose Material 3.
- Use Navigation Compose destinations: Home, ROI setup, Review, Processing, Result/History, Settings/Diagnostics/Update.
- Use one screen-level ViewModel per destination where state ownership warrants it.
- Expose immutable `StateFlow<UiState>` and accept UI events through explicit functions.
- Observe WorkManager/job-store state lifecycle-safely; UI memory is never the job source of truth.
- Replace `VideoView` with Media3 Player for source and result preview.

### Increment 2.1 — Guided creator workflow

- Home: source picker, recent result, concise purpose statement.
- ROI: large preview, drag/resize handles, zoom, scrub, corner presets, reset, live crop, OCR readiness.
- Review: recommended defaults; advanced controls collapsed by default; estimated duration.
- Processing: stage timeline, percent, elapsed time, ETA, candidate/confirmed counts, cancel.
- Result: immediate preview, duration, clip count, location, save/share/open/discard/create-another.
- Diagnostics and updater controls do not appear in the primary creation path.

### Increment 2.2 — Accessibility and performance

- Material 3 blue/teal theme, dynamic color, dark mode, consistent typography/shapes/spacing.
- TalkBack descriptions, 48dp targets, WCAG contrast, large fonts, portrait/landscape, RTL, tablet, and keyboard support.
- Respect reduced-motion settings.
- Explicit user-facing errors for invalid source, invalid frame, OCR unavailable/timeout, no results, cancellation, export failure, and signing migration.
- Compose screenshot tests for light/dark, phone/tablet, empty, processing, error, and result.
- Macrobenchmark and Baseline Profile modules for startup, navigation, ROI interaction, and result opening.
- No retained Activity or bitmap; bounded preview memory; no visible navigation jank on reference devices.

### Increment 2.3 — Shipping gate

Run unit tests, lint, release instrumentation, Worker lifecycle tests, accessibility checks, screenshot tests, performance benchmarks, complete A/B QA, API 24/29/35/current-stable checks, and at least one physical-device codec/export test.

Publish `1.0.0` only when:

- Phase 1 remains green.
- No blocker or critical defect remains.
- Exact release APK passes all gates.
- Install, first launch, permissions, update, cancellation, process death, reopen, share, and clean reinstall pass.
- Signing secrets are absent from Git.
- Release artifact signature is verified.
- QA note states `READY TO SHIP`.

## 20. Release and distribution procedure

For every app/code increment that is not explicitly local-only:

1. Read the latest QA note.
2. Implement the scoped change and regression test.
3. Run `./gradlew clean assembleDebug assembleRelease` and `./gradlew test`.
4. Run all additional focused tests required by the increment.
5. Stop if a required build or test fails; do not publish.
6. Increment `versionCode` and patch `versionName`; rebuild if validation used the previous version.
7. Commit only scoped files and preserve unrelated user work.
8. Push the default branch.
9. Create and push tag `v<versionName>`.
10. Publish the GitHub release APK.
11. Verify release workflow success and verify the APK URL is reachable.
12. Download and test that exact APK against Video A.
13. Pull output and compare it with host-side Video B.
14. Update the QA note with evidence and first failure.
15. After the release asset exists, prepend the release to `app-update.json`, commit, and push.
16. Verify raw `app-update.json` exposes the new version and reachable APK.
17. Run a connected-device smoke test when available; explicitly report when no device is connected.

Phase 1 releases MUST be labeled test builds until the full gate passes. Publication of a test build does not imply Phase 1 success.

## 21. Failure handling and stop conditions

- Never infer success from logs alone.
- Never infer success from file existence, duration, or absence of OCR timeout alone.
- Require output MP4, structured report, and host comparison.
- Preserve all evidence for failed runs.
- Stop autonomous code changes for human review when the same failure signature occurs in three consecutive patch cycles, credentials are unavailable, a destructive migration is required, or the required fix expands product scope.
- Local QA cannot override a conflicting hosted result; investigate the disagreement.
- Hosted QA cannot overwrite local evidence; retain both reports.

## 22. Required artifacts per QA run

Every pass or failure MUST retain:

```text
qa-run.json
logcat.txt
scan-report.json
output.mp4, when produced
ffprobe-output.json
visual-comparison.json
audio-comparison.json
screenshots/
apk-sha256.txt
fixture-sha256.txt
```

Artifacts MUST identify run ID, app version, commit, release tag, scanner version, emulator/device model, API level, and UTC timestamps.

## 23. Definition of done

The app is functionally working only when the scanner independently derives the ten transitions from Video A, Media3 exports the ten exact windows, the output passes semantic/timing/container/audio/visual/lifecycle checks against Video B, and this occurs twice consecutively on clean emulators with hosted/local agreement.

The app is ready to ship only after Phase 2 and the `READY TO SHIP` gate also pass.

Anything less is an intermediate test build.

## 24. Technical references

- Android Gradle Plugin 8.13 compatibility: <https://developer.android.com/build/releases/agp-8-13-0-release-notes>
- Espresso-Intents: <https://developer.android.com/training/testing/espresso/intents>
- `MediaMetadataRetriever`: <https://developer.android.com/reference/android/media/MediaMetadataRetriever>
- WorkManager integration testing: <https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing>
- ML Kit text recognition: <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- Media3 Transformer: <https://developer.android.com/media/media3/transformer>
- Media3 composition: <https://developer.android.com/media/media3/transformer/composition>
- Android architecture recommendations: <https://developer.android.com/topic/architecture/recommendations>
