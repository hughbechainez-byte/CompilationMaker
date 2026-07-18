# Most recent Android QA

- Result: **PASS** — release v0.17.27 completed Video A, exported a verified compilation, and met the under-four-minute target.
- App/version: 0.17.27 (versionCode 59), app commit `c288061`, release tag `v0.17.27`.
- Release APK: SHA-256 `0D15DBDBE5257C39DFA31D35226CCD98183A2F7AD09733E981D9A7000AA06CD4`; Android test APK SHA-256 `AA24C26EBFFF8F4BC0FAE5220FCE729B3744752F9C0B3827C77128181AF5591E`.
- Fixture: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; API 35 `emulator-5554` installed and exercised the signed v0.17.27 APK.
- Current benchmark: 159.804 seconds end-to-end, versus published v0.17.26 at 357.280 seconds — 197.476 seconds faster (2.24x). Scanner time was 58.074 seconds versus 249.950 seconds (4.30x).
- Scanner evidence: canonical `number-change-detector-v0.6.1` path, 121 PTS checkpoints, 244 OCR inferences, ten candidates, ten confirmed transitions, no fallback, and exact PTS marks from 30.000s through 3560.000s.
- Output evidence: ten exact semantic clips, 400.000-second readable MP4, 31,298,138 bytes; instrumentation reported `OK (1 test)`.
- First unresolved causal failure: none in the API 35 run. The physical Moto device was not updated because its preinstalled package has an incompatible signing certificate; that is an external test-device state, not an app failure.
