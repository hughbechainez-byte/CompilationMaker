# CompilationMaker Iteration Ledger

## v0.17.21 — accepted experimental baseline

- Commit/artifact: `845cbd6`; exact released `CompilationMaker-v0.17.21.apk` SHA-256 `1B9B1811275892F63B9F322B7CF523F3ED3AFE01C3AEFD86B477FF34C1602B10`.
- Hypothesis/result: bounded 3/1 semantic/visual recursion would complete within ten minutes; completed in 515.464s with 6/10 transitions, 60% recall, zero exported false positives, and a 286.601s output.
- Earliest failures: `null -> 1` candidate timed out during linear refinement; `6 -> 7`, `8 -> 9`, and `9 -> 10` had candidate coverage but unresolved 6/9 OCR evidence or sequence rejection.
- Validation: exact release APK on API 35 emulator, production WorkManager pipeline, scan report, output pull, and host `ffprobe` comparison against Video B.
- Decision/next hypothesis: retain v0.17.21 as the comparison baseline; first replace linear first-number refinement with bounded persistent-state bisection, then address 6/9 recognition using image evidence.
