# Most Recent Android QA Note

- Status: FAIL — exact release test reached the scan budget at 360 seconds of Video A and returned no results.
- App/version: 0.17.17 (versionCode 49), scanner commit `22edbb5`, release tag `v0.17.17`.
- Published APK: `app-release.apk`, SHA-256 `89AE3051630091D1CC45E856FF555E664346FD1B863509FAE6D8393F034F39E9`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`.
- Emulator evidence: API 35 `emulator-5554`; seven checkpoints reached, three retained intervals, all candidate-local timeouts, zero confirmations, Worker `NO_RESULTS` after 199.694 seconds.
- Result: no false clip/export was produced; Video A output comparison is unavailable because the semantic gate failed before export.
- First unresolved causal failure: the generic coarse-scan deadline is incompatible with mandatory 61 checkpoints multiplied by five state samples. Budget direct checkpoint scanning for the complete plan, then recursively refine complete intervals with candidate-local limits.
