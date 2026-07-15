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
