# CompilationMaker Agent Instructions

## Standing release policy

When Dave asks for an app update or code change and does not explicitly say it is local-only, complete the distribution workflow automatically:

1. Run `./gradlew clean assembleDebug assembleRelease` and `./gradlew test`.
2. Increment `versionCode` and the patch `versionName` for the release, then rebuild if the validated build did not already contain that version.
3. Commit only scoped files and preserve unrelated user work.
4. Push the default branch, create and push the matching `v<version>` tag, and publish the GitHub release APK.
5. Verify the GitHub Actions release succeeds and the APK download is reachable.
6. Update `app-update.json` only after the release asset exists, prepend its entry to `updates`, commit, and push the feed update.
7. Verify the raw `app-update.json` feed exposes the new version and APK to the app.
8. Run connected-device smoke tests when a device is available; clearly report when none is connected.

Never publish an update when required builds or tests fail unless Dave explicitly directs otherwise.
