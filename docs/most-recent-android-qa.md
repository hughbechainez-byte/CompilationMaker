# Most Recent Android QA Note

- Status: FAIL — exact published APK did not finish the Video A gate within a viable scan duration.
- App/version: 0.17.15 (versionCode 47), scanner commit `7defc78`, release tag `v0.17.15`.
- Published APK: `CompilationMaker-v0.17.15.apk`, SHA-256 `B7CCDD276707CD7246B9490529FD41AB2860DADB2527DCA39CA056B4287B6C50`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; 9/62 checkpoints reached 540s at 14%, with each checkpoint callback taking roughly 5–9 seconds; test was force-stopped before output.
- Result: no APK/runtime crash and no visual-fallback success, but no 10-clip/400-second comparison was possible.
- First unresolved causal failure: five checkpoint OCR samples each create a MediaCodec decoder. Use the existing retriever for structured five-sample checkpoint evidence; use MediaCodec only to investigate retained candidate intervals.
