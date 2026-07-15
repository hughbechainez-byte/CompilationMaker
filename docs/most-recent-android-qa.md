# Most recent Android QA

- Result: **FAIL** — exact published v0.17.26 remained `REFINING` at the 15-minute instrumentation deadline; no export or scan report was produced.
- App/version: 0.17.26 (versionCode 58), app commit `583f602`, release tag `v0.17.26`; update-feed commit `b355540`.
- Published APK: SHA-256 `975B1B03BD21291753CD6DA3918887A173555F7AF0DE7CBFBFFEAF5655E8D7A7`; test APK SHA-256 `632E9D43161BDB360C17ABFC30B0B1894EFFCA87FBC0BBEF21CE695F80ABD142`.
- Fixtures: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; host-only Video B SHA-256 `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`. Video B was absent from the emulator.
- Emulator evidence: API 35 `emulator-5554`; Activity closed into PiP and Launcher resumed; WorkManager foreground service and notification were observed; instrumentation runtime 914.450 seconds.
- Scanner evidence: 61 checkpoints, 14 candidates, 30 recursive probes, 10 semantic leaves, 1,312 OCR calls, seven OCR timeout observations, and six confirmed valid transitions (`null -> 1` through `5 -> 6`); no false 6/9 oscillation was observed.
- Clip-plan evidence: confirmation budget reached zero before candidate 7; output and scan report were not produced, so SSIM/audio/duration comparison against host-only Video B was unavailable.
- First unresolved causal failure: candidate confirmation still consumes the global budget while sequentially processing the ten semantic leaves; the run stopped in `REFINING` after six confirmations, and the instrumentation deadline then force-stopped the app.
