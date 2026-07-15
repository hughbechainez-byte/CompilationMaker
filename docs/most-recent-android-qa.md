# Most Recent Android QA Note

- Status: FAIL — exact published v0.17.23 restored the Activity-closed semantic result to 7/10 transitions (6 exported clips) and eliminated candidate timeouts, but the primary fixture still misses three transitions.
- App/version: 0.17.23 (versionCode 55), commit `4b1701a2213b5f503322fb6afba695532a82f3f9`, release tag `v0.17.23`.
- Published APK: `CompilationMaker-v0.17.23.apk`, SHA-256 `24C07854FB337F1042B683FA4BD5A6EF99C65C8817026A841B7C91BFE9DFD197`; test APK SHA-256 `0600DFC65A35396A1E5DD6FD773BE95EE59F451660973519CCE8451FE9F3278D`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; host-side Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; Activity closed with launcher resumed, foreground WorkManager service verified, 61 checkpoints, 22 complete candidates, 625 OCR calls, zero candidate timeouts; 438.614-second instrumentation / 416.463-second scan.
- Output comparison: 330.601 seconds, 6 clips, 3,144,919 bytes; Video B is 400.000 seconds, 10 clips, 4,495,038 bytes; precision 100%, recall 70% (7 transition matches, including `null -> 1`).
- First unresolved causal failure: checkpoint voting repeatedly flips `6` and `9`, preventing `6 -> 7`, `8 -> 9`, and `9 -> 10` despite complete candidate coverage; add confidence-preserving topology adjudication before any interval-bridging change.
