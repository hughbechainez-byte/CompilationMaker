# Most Recent Android QA Note

- Status: FAIL — exact published APK completed and exported, but Video A produced six rather than ten semantic clips.
- App/version: 0.17.21 (versionCode 53), scanner commit `845cbd6`, release tag `v0.17.21`.
- Published APK: `CompilationMaker-v0.17.21.apk`, SHA-256 `1B9B1811275892F63B9F322B7CF523F3ED3AFE01C3AEFD86B477FF34C1602B10`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; 61 timeline retrievals, 22 adaptive intervals, 50 recursive probes, 7 semantic leaves, and 6 confirmed transitions; test duration 515.464 seconds.
- Output comparison: 286.601041 seconds, 6 clips, 2,775,864 bytes; Video B is 400.000000 seconds, 10 clips, 4,495,038 bytes.
- First unresolved causal failure: bounded investigation completes, but four transitions lack stable sequential evidence. Prioritize extra probes only for unresolved intervals adjacent to the accepted number sequence.
