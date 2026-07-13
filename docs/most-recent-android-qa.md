# Most Recent Android QA Note

## Current test

- Status: BLOCKED — Phase 1 A→output→B validation has not yet run.
- App/version: 0.17.11 (versionCode 43), pending release tag.
- Emulator: `CompilationMaker_API35`, API 35, `emulator-5554`.
- Release APK SHA-256: pending v0.17.11 release asset.
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

## First unresolved causal failure

Make Video A reliably visible to the hosted API 35 MediaStore query, then rerun the deterministic picker/Worker handoff. No scanner, export, or A/B correctness claim is made by this test.
