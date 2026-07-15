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
