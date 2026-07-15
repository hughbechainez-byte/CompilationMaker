# Most Recent Android QA Note

- Status: FAIL — exact published v0.17.22 completed with its Activity closed, but fixed candidate deadlines reduced Video A to 1/10 semantic clips.
- App/version: 0.17.22 (versionCode 54), commit `f6c96ef02b39f123451cd563fd257880c8bf9679`, release tag `v0.17.22`.
- Published APK: `CompilationMaker-v0.17.22.apk`, SHA-256 `CC58B7B9420B27AEF105495E43A2A2ACE62272A9E059DED4C9EBB4C9D362F931`; test APK SHA-256 `0600DFC65A35396A1E5DD6FD773BE95EE59F451660973519CCE8451FE9F3278D`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; host-side Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; Activity closed, foreground WorkManager service verified, 61 checkpoints, 22 complete candidates, 50 recursive probes, 7 semantic leaves, 560 OCR calls, 7 candidate timeouts; 649.353-second instrumentation / 626.967-second scan.
- Output comparison: 46.784 seconds, 1 clip, 404,143 bytes; Video B is 400.000 seconds, 10 clips, 4,495,038 bytes; precision 100%, recall 10%.
- First unresolved causal failure: the fixed 16-second candidate-local wall deadline expires under background scheduling and discards valid sequential refinements; repair this independently before 6/9 topology adjudication.
