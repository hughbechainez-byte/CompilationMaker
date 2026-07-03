# Phase 12 - Auto Release Pipeline for Every Version

- Added GitHub Actions release workflow: `.github/workflows/release-on-tag.yml`.
- Release workflow triggers on `push` tags matching `v*`.
- It builds `app-debug.apk` and publishes a GitHub release automatically.
- `app-update.json` is still used as the app-facing manifest and is auto-updated on `release` publish by the existing workflow.
- Bumped app version to `versionCode=11`, `versionName="0.11.0"` so update checks can now detect releases as newer than older builds.
- Added placeholder `app-update.json` with release URL and APK URL format for immediate in-app discovery once the first tagged release is published.

## Release process for future versions
- Push a tag like `v0.11.0` (or later semver).
- GitHub Actions builds the APK and publishes the release.
- Manifest workflow updates `app-update.json` from release metadata, including attached APK URL.
