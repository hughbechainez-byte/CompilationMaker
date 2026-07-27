# Most recent Android QA

- Result: **PASS** — release v0.17.30 updated queue/compile startup wiring and crash-log persistence, with low/medium-risk pixel scan profile labels explicitly exposed.
- App/version: 0.17.30 (versionCode 62), app commit `9a5f6ce`, release tag `v0.17.30`.
- Local debug APK: SHA-256 `96F43E6C7407EA39C63691BB1887D10826746F14FB59E9EA475A821D13B8B442`, 30,590,125 bytes.
- Local release APK: SHA-256 `9150300F3F91272EF1A8E6E1D2604C9A9F112749729C4F4C84D83B314716767D`, 27,184,591 bytes.
- Hosted release APK: SHA-256 `9150300F3F91272EF1A8E6E1D2604C9A9F112749729C4F4C84D83B314716767D`, 27,184,591 bytes, versionCode 62, and release asset verified at GitHub `releases/download/v0.17.30/app-release.apk`. Raw feed on branch `codex/numdetect` exposes `v0.17.30`.
- Fixture: no connected device for this pass; Video A fixture hash retained from prior pass (`DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`) as a baseline until an emulator rerun.
- Current benchmark: compile build/test only; no refreshed Video A end-to-end scan benchmark in this cycle.
- Scanner evidence: `compilationScanProfiles()` now exposes explicit `Low-risk pixel scanner (FAST 30s)` and two medium-risk lanes, while canonical production profile remains the exact PTS path.
- Output evidence: no new on-device output verification in this pass.
- First unresolved causal failure: no connected Android device for on-device smoke test, and initial cleanup failed due locked `app/build` outputs until `./gradlew --stop` was executed.
