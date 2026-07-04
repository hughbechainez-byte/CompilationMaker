# Phase 14.2 - In-App Update Download

## Cause
- The update notification and manual update path used a GitHub download URL as an `ACTION_VIEW` intent.
- Tapping update actions therefore opened the browser instead of letting the app manage the update.

## Fix
- Manual Check for updates now checks the manifest in the background.
- If a newer version exists, the app shows an install prompt.
- Choosing Install downloads the APK into app cache in the background.
- The app then opens Android's package installer using a `FileProvider` content URI.
- Added `REQUEST_INSTALL_PACKAGES` and update cache file-provider paths.

## Note
- Android may ask once to allow installs from CompilationMaker. That is a system security setting and cannot be skipped by the app.
