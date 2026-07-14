# Most Recent Android QA Note

- Status: FAIL — exact release completed all timeline retrievals and exported, but Video A produced three rather than ten semantic clips.
- App/version: 0.17.18 (versionCode 50), scanner commit `2440743`, release tag `v0.17.18`.
- Published APK: `CompilationMaker-v0.17.18.apk`, SHA-256 `FC39B1EEF47FD3C5853FD691C076FEB274A352415655D1CF7BBCA106D3B363DA`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; 62/62 timeline retrievals completed; 22 retained candidate intervals; three semantic confirmations (`3 -> 4`, `4 -> 5`, `7 -> 8`); test duration 444.567 seconds.
- Output comparison: 143.306000 seconds, 3 clips, 1,509,282 bytes; Video B is 400.000000 seconds, 10 clips, 4,495,038 bytes.
- First unresolved causal failure: flat confirmation spends work on visual-only intervals and does not locate multiple semantic changes inside a coarse interval. Recursively classify and split complete intervals before doing boundary refinement.
