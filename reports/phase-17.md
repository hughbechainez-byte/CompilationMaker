# Phase 17 - Crash Log Access

## What changed

- Added a process-level crash recorder that writes the last uncaught exception to app storage.
- Added an `Open crash log` button to the main screen.
- The crash log dialog lets the user view the saved stack trace, copy it to the clipboard, or clear it.
- The app now nudges the user if a crash log exists after launch.

## Verification

- `./gradlew.bat testDebugUnitTest`
- `./gradlew.bat assembleDebug`

