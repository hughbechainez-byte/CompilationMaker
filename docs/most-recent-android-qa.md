# Most Recent Android QA Note

- Status: FAIL — exact published APK remained in FINALIZING beyond the ten-minute fixture deadline and produced no output.
- App/version: 0.17.20 (versionCode 52), scanner commit `7cd2f5b`, release tag `v0.17.20`.
- Published APK: `CompilationMaker-v0.17.20.apk`, SHA-256 `6DDC863EDD58CFBABF67B47168F08E803F3015EECE8C5F2A588E5F2C1BADFB12`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; exactly 61 timeline retrievals and 22 adaptive candidate intervals completed; instrumentation failed after 623.341 seconds with state `FINALIZING` rather than `SUCCEEDED`.
- Output comparison: no v0.17.20 output was produced; Video B remains 400.000000 seconds, 10 clips, 4,495,038 bytes.
- First unresolved causal failure: production stable-endpoint intervals still allow 15 five-sample probes. Cap production semantic recursion to three probes while retaining deeper deterministic algorithm tests.
