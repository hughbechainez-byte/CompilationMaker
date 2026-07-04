# Phase 13.2 - Installable GitHub APK

## Cause
- The release workflow built both release and debug APKs.
- It searched the release output folder first and uploaded the first APK it found.
- The release APK has no signing configuration in this project, so Android rejects it with a generic "App not installed" message.

## Fix
- GitHub releases now build and upload `app/build/outputs/apk/debug/app-debug.apk`.
- This debug APK is signed by the Android Gradle plugin debug key and is installable from the GitHub release asset.
- The in-app update manifest now points to `v0.13.2`.
