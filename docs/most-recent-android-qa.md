# Most recent Android QA

- Result: **PASS** — release v0.17.34 adds batch compilation, richer PiP status, compiled/original detection, side-by-side review, and explicit original deletion consent.
- App/version: 0.17.34 (versionCode 66), app commit `c75dce9`, release tag `v0.17.34`.
- Local debug APK: SHA-256 `96F43E6C7407EA39C63691BB1887D10826746F14FB59E9EA475A821D13B8B442`, 30,590,125 bytes.
- Local release APK: SHA-256 `9150300F3F91272EF1A8E6E1D2604C9A9F112749729C4F4C84D83B314716767D`, 27,184,591 bytes.
- Hosted release APK: SHA-256 `39313B53CE2A244EC62696392D448020526513B4CEDCE44E8B6DE48E6D6E1FFE`, 27,201,470 bytes, release asset verified at GitHub `releases/download/v0.17.34/app-release.apk`. Raw feed on branch `codex/numdetect` exposes `v0.17.34`.
- Fixture: no connected device for this pass; Video A fixture hash retained from prior pass (`DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`) as a baseline until an emulator rerun.
- Current benchmark: clean debug/release build and full unit tests passed; no refreshed batch end-to-end instrumentation benchmark in this cycle.
- Scanner evidence: `compilationScanProfiles()` now exposes explicit `Low-risk pixel scanner (FAST 30s)` and two medium-risk lanes, while canonical production profile remains the exact PTS path.
- Output evidence: no new on-device output verification in this pass.
- First unresolved causal failure: batch MediaStore/WorkManager instrumentation and deletion-permission UI flow still need a connected-device run; clean build initially hit a locked `app/build` output and passed after `./gradlew --stop`.
