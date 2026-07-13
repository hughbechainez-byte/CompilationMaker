# Most Recent Android QA Note

## Current test

- Status: BLOCKED — Phase 1 A→output→B validation has not yet run.
- App/version: 0.17.6 (versionCode 38), app commit `f66bb77`.
- Emulator: `CompilationMaker_API35`, API 35, `emulator-5554`.
- Release APK SHA-256: `7ADBCD0AC7FF03DBB7CBF2AC10F727D81FDDD25A100B8A326D0D524B481C5E38`
- Video A SHA-256: `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`
- Video B SHA-256: `B417C1C5F36EC3D91129AD986EB32D9DF4813D25E1854C5ADE974F2B8A1C318C`

## Results

- `./gradlew clean assembleDebug assembleRelease test` passed with AGP 8.13.2 and Gradle 8.13.
- The release APK and matching release-signed instrumentation APK installed on the API 35 emulator.
- `VideoPickerHandoffTest` passed: the `ACTION_OPEN_DOCUMENT` result was stubbed, returned to the app, and persisted the selected URI in the durable draft state.
- The picker no longer routes to a hard-coded DocumentsUI package. This fixes the prior return-path failure and makes Espresso-Intents interception reliable.
- The local QA runner now waits for a booted AVD, rechecks readiness before each run, and launches with the SwiftShader renderer and a 2 GB guest-memory target.
- No physical device was connected.

## First unresolved causal failure

Run the complete deterministic Video A picker/Worker flow, pull `qa-run.json` and the output MP4, then compare it with host-only Video B. No scanner, export, or A/B correctness claim is made by this test.
