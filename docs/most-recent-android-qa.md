# Most Recent Android QA Note

## Current test

- Status: PARTIAL PASS — release-signed Video A scan/fallback/export/verification passed; hosted QA and host-only Video B comparison remain.
- App/version: 0.17.13 (versionCode 45), release tag `v0.17.13`, OCR fix commit `585e139`, QA commit `d297a7b`.
- Emulator: `CompilationMaker_API35`, API 35, `emulator-5554`.
- Published release APK SHA-256: `74A8410693ACE928B12E4895072130679BDC99140AF093148385F6FED8D75D91`
- Video A SHA-256: `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`
- Video B SHA-256: `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`

## Results

- `./gradlew clean assembleDebug assembleRelease test` passed with AGP 8.13.2 and Gradle 8.13.
- The release APK and matching release-signed instrumentation APK installed on the API 35 emulator.
- `VideoPickerHandoffTest` passed: the `ACTION_OPEN_DOCUMENT` result was stubbed, returned to the app, and persisted the selected URI in the durable draft state.
- The picker no longer routes to a hard-coded DocumentsUI package. This fixes the prior return-path failure and makes Espresso-Intents interception reliable.
- The local QA runner now waits for a booted AVD, rechecks readiness before each run, and launches with the SwiftShader renderer and a 2 GB guest-memory target.
- No physical device was connected.
- The hosted Ubuntu QA workflow initially failed before tests because `gradlew` lacked execute permission; this patch adds the required `chmod +x` step.
- The deterministic picker test now resolves staged Video A from MediaStore, returns that URI through the picker contract, and verifies the Worker enters an active state before cancellation.
- Hosted QA was blocked by disabled KVM and a shared-storage fixture push failure; the workflow now enables `/dev/kvm` access and the runner creates/retries the Downloads staging path.
- The signed `CompilationMaker-v0.17.9.apk` release asset was published and verified reachable before this update feed was promoted.
- Manual hosted-QA dispatch now resolves the latest published release rather than an obsolete hard-coded tag.
- The signed `CompilationMaker-v0.17.10.apk` release asset was published and verified reachable before this update feed was promoted.
- Hosted QA run `29236205919` exposed a false pass: the test could not find Video A in MediaStore, but the runner continued after the instrumentation failure. The runner now fails for missing MediaStore visibility and preserves the instrumentation exit status.
- OCR confirmation now uses per-frame, per-attempt, per-candidate, and candidate-scaled overall deadlines; confirmed transitions are stored incrementally.
- OCR crops are padded and aspect-preservingly scaled to at least 128 px, variants are prepared lazily, ML Kit empty/error results stay candidate-local, and strong visual candidates provide a labeled fallback clip plan.
- Scan progress maps to the first 43% of total-job progress and Worker progress is monotonic across phase changes.
- Release-signed API 35 `VideoPickerHandoffTest` passed with the real staged Video A MediaStore URI.
- Release-signed three-minute checkpoint Video A test completed in visual-fallback mode: 2 inferred transitions, 2 clips, verified 94,013 ms output, 902,471 bytes, and Worker `SUCCESS`.
- OCR used 128x144 prepared raw inputs; observed attempts completed in approximately 140-170 ms and stopped after a valid parsed digit.
- The legacy UI runner's Downloads-provider URI failed source setup on this emulator; the deterministic end-to-end instrumentation uses the readable MediaStore URI and now covers scan through verified export.
- Hosted run `29240352815` proved `adb` can exit 0 while instrumentation reports `INSTRUMENTATION_CODE: -1`; the runner now parses failure markers, and the test imports the staged fixture into MediaStore under test-only all-files access when scanning is unavailable.

## First unresolved causal failure

Run the same v0.17.12 release test in hosted QA and complete the host-only Video B comparison; local Video A export is verified.
