# CompilationMaker Iteration Ledger

## v0.17.21 — accepted experimental baseline

- Commit/artifact: `845cbd6`; exact released `CompilationMaker-v0.17.21.apk` SHA-256 `1B9B1811275892F63B9F322B7CF523F3ED3AFE01C3AEFD86B477FF34C1602B10`.
- Hypothesis/result: bounded 3/1 semantic/visual recursion would complete within ten minutes; completed in 515.464s with 6/10 transitions, 60% recall, zero exported false positives, and a 286.601s output.
- Earliest failures: `null -> 1` candidate timed out during linear refinement; `6 -> 7`, `8 -> 9`, and `9 -> 10` had candidate coverage but unresolved 6/9 OCR evidence or sequence rejection.
- Validation: exact release APK on API 35 emulator, production WorkManager pipeline, scan report, output pull, and host `ffprobe` comparison against Video B.
- Decision/next hypothesis: retain v0.17.21 as the comparison baseline; first replace linear first-number refinement with bounded persistent-state bisection, then address 6/9 recognition using image evidence.

## v0.17.22 — rejected background checkpoint

- Commit/artifact: `f6c96ef`; exact released APK SHA-256 `CC58B7B9420B27AEF105495E43A2A2ACE62272A9E059DED4C9EBB4C9D362F931`.
- Hypothesis/result: bounded first-state bisection recovered `null -> 1` at 29.765s, but the Activity-closed run completed in 649.353s with only 1/10 transitions and seven candidate timeouts.
- Earliest failure: the fixed 16-second candidate wall deadline is sensitive to background scheduling; all nine false negatives retained candidate coverage.
- Validation: exact GitHub APK on API 35, Activity closed, foreground WorkManager service verified, 61 checkpoints, report/output pull, and host Video B comparison.
- Decision/next hypothesis: retain the first-state algorithm but reject the fixed deadline; ship a separate OCR-work-scaled candidate budget before topology adjudication.

## v0.17.23 — accepted timeout-repair checkpoint

- Commit/artifact: `4b1701a`; exact released APK SHA-256 `24C07854FB337F1042B683FA4BD5A6EF99C65C8817026A841B7C91BFE9DFD197`.
- Hypothesis/result: a bounded OCR-work-scaled candidate guard would preserve Activity-closed progress; candidate timeouts fell from seven to zero and recall recovered from 10% to 70% in 438.614s.
- Earliest failures: candidate coverage is complete, but `6 -> 7`, `8 -> 9`, and `9 -> 10` remain unresolved while coarse votes alternate between `6` and `9`.
- Validation: exact GitHub APK on API 35, Activity closed with launcher resumed, foreground WorkManager service verified, 61 checkpoints, report/output pull, and host Video B comparison.
- Decision/next hypothesis: accept the timeout repair; preserve this runtime baseline and add a confidence-preserving, pure topology adjudicator only for ML Kit `6`/`9` results.

## v0.17.24 — accepted topology / state-timeline checkpoint

- Commit/artifact: `dc218dc`; exact released APK SHA-256 `684127A92CE25E6265A90CE4809589111BA05BC841E7D22D1EF427B512D77D99`.
- Hypothesis/result: confidence-preserving ML Kit evidence plus conservative two-threshold glyph topology would eliminate false `6/9` states; the Activity-closed run reached TP=10, FP=0, FN=0, 100% recall, 14 candidates, 556 OCR calls, and zero timeouts in 556.466s.
- Earliest failure: Gradual style padding and merge-gap policy combines the first two exact semantic windows, then previous-sync-sample muxing expands nine planned clips to 473.059s instead of ten clips/400s.
- Validation: exact GitHub APK on API 35, Launcher resumed, foreground service verified, 61 checkpoints, complete raw/final/topology evidence, report/output pull, host-only Video B ffprobe/SSIM/audio diagnostics, and five-line Desktop log.
- Decision/next hypothesis: accept topology as the scanner baseline; enforce exact unpadded non-overlapping clip windows and replace sync-seeking mux export with Media3 Transformer composition plus automated A/B gates.

## v0.17.25 — rejected report/export checkpoint

- Commit/artifact: `0263796`; exact released APK SHA-256 `E76B4910F6FA2C3D90B9FD702D2FAD33F1524B18238EB0504FCD3F1BA8FC6B40`.
- Hypothesis/result: exact Media3 windows plus provisional recovery would produce a testable output; the Activity-closed run stayed healthy for 898.823s, completed 61 checkpoints, then failed before export on `Forbidden numeric value: Infinity`.
- Earliest failure: confirmed plan points used a non-finite visual-score sentinel that Android `JSONObject` refuses; eight confirmed candidates were also duplicated as provisional-high points, widening ten windows to 496.096s.
- Validation: exact GitHub APK on API 35, Launcher resumed, foreground service/notification verified, Video B absent from emulator, full logcat, instrumentation transcript, restored failure screenshot, and host-only fixture hashes.
- Decision/next hypothesis: reject v0.17.25; make every report number finite, suppress provisional evidence for already-confirmed candidates, and gate the pure plan at ten points/ten clips/400.000s before the next release.

## v0.17.26 — rejected confirmation-budget checkpoint

- Commit/artifact: `583f602`; exact GitHub release `v0.17.26` SHA-256 `975B1B03BD21291753CD6DA3918887A173555F7AF0DE7CBFBFFEAF5655E8D7A7`.
- Hypothesis/result: finite report values and confirmed-candidate suppression would reach export; the exact Activity-closed run stayed alive, completed 61 checkpoints/14 candidates/30 probes, and confirmed six valid transitions before remaining `REFINING` at 914.450s.
- Earliest failure: the global confirmation budget reached zero after `5 -> 6`; candidate 7 onward remained unconfirmed and the 15-minute instrumentation deadline stopped the run before plan selection, report copy, or Media3 export.
- Validation: exact published APK on API 35, Launcher resumed, PiP Activity closure, foreground WorkManager service/notification, Video B absent from emulator, full logcat, instrumentation transcript, and fixture hashes.
- Decision/next hypothesis: keep the report-safe/deduplication fix; pause here as requested. The next iteration must make confirmation budget/resumption explicit before pursuing export/A-B metrics.
