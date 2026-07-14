# Most Recent Android QA Note

- Status: FAIL — the exact published APK completed, but the Video A semantic gate found only four of ten required clips.
- App/version: 0.17.16 (versionCode 48), scanner commit `c03a6d0`, release tag `v0.17.16`.
- Published APK: `app-release.apk`, SHA-256 `82C56D72BA71B048508A7F9A7FD02BF2A429917D364D990FC36567BD003AD755`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; published release APK completed in 227.28 seconds; 7 retained candidates, global deadline reached after candidate 6, four OCR-confirmed transitions, Worker `SUCCESS`.
- Output comparison: 191.060375 seconds, 4 clips, 1,843,038 bytes; Video B is 400.000000 seconds, 10 clips, 4,495,038 bytes.
- First unresolved causal failure: the overall confirmation deadline stops investigations before all retained semantic intervals are resolved. Recursively investigate complete intervals and continue after candidate-local timeouts; no global cutoff may discard valid transitions.
