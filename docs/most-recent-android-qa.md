# Most recent Android QA

- Result: **PASS** — release v0.17.28 completed Video A, preserved the exact clip plan, and beat the v0.17.27 benchmark by more than 30 seconds.
- App/version: 0.17.28 (versionCode 60), app commit `0d773ff`, release tag `v0.17.28`.
- Local validation APK: SHA-256 `0358B099097E6862763685231BE81382198706451448B62B9940DDA2C55E8BD9`; Android test APK SHA-256 `C0561307476CFB92A8C83AB6B5D344F2C0C8C518C1EC177285C0B270DB38C46B`.
- Hosted release APK: SHA-256 `7FD595B3D68853541F05751C6EC3DC944B9FF27FE10277CC33C8A937B5310295`, 27,183,856 bytes, versionCode 60, and the verified release certificate. The raw update feed exposes `v0.17.28`; a v0.17.27 emulator install displayed `Available updates` / `v0.17.28 (newer)` before restoring the hosted APK.
- Fixture: Video A SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`; API 35 `emulator-5554` installed and exercised the signed v0.17.28 APK.
- Current benchmark: 89.011 seconds end-to-end, versus published v0.17.27 at 159.804 seconds — **70.793 seconds faster** (1.80x). The direct Fast-profile scan took 55.523 seconds and Media3 export took 27.072 seconds.
- Scanner evidence: canonical `number-change-detector-v0.6.1` path, 121 PTS checkpoints, 244 OCR inferences, ten candidates, ten confirmed transitions, no fallback, and exact PTS marks from 30.000s through 3560.000s.
- Output evidence: ten exact semantic clips and a 400,000ms clip plan; final output was 24,858,296 bytes with SHA-256 `0B7997EB3FBC72AEAFB044521A38502497ADECF409B57B8C184638EACF61353C`. Media verification retained H.264/AAC, measured 400.016 seconds externally, and matched the v0.17.27 decoded video at SSIM 1.000000.
- First unresolved causal failure: none in the API 35 release run. The connected Moto remains on v0.17.1 under an incompatible signing certificate, so it was not modified; that is an external device state, not an app failure.
