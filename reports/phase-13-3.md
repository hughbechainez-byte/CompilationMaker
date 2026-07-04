# Phase 13.3 - Stable Sideload Signing

## Cause
- Android only allows an APK update when the package name and signing certificate match the app already installed on the device.
- `v0.13.2` was installable as a standalone APK, but it was signed with GitHub's runner-generated debug certificate.
- Earlier local APKs were signed with this PC's debug certificate, so Android rejected the GitHub APK as an update with the generic "App not installed" message.

## Fix
- Added the existing local debug keystore as `keystores/compilationmaker-update.keystore`.
- Added a `sideloadUpdate` signing config and applied it to debug and release builds.
- GitHub releases now build and upload the signed release APK instead of a runner-specific debug APK.
- The workflow verifies the APK signature before publishing the release asset.

## Note
- If a device has already installed a build signed with GitHub's old runner-generated debug key, Android still cannot update that install to this stable key. That one device install must be removed once, then future releases can update normally from the stable sideload key.
