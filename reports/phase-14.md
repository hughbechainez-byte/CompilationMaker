# Phase 14 - Clean Sideload Install Path

## Cause
- `v0.13.3` verified as a valid APK and matched the stable signing key, so continued install failure after uninstall points to device-side package residue/conflict or package installer compatibility.
- Android can keep a package installed for another user/profile or keep conflict state after uninstall, which still blocks the same package name.
- The APK also used modern native-library mmap packaging (`extractNativeLibs=false`), which is valid but less forgiving for sideload/package-installer edge cases.

## Fix
- Changed the public application id from `com.example.compilationmaker` to `com.hughbechainez.compilationmaker`.
- Kept the same stable sideload signing key.
- Enabled stable v2 and v3 APK signing schemes for Pixel-compatible installer support.
- Switched native library packaging to legacy extraction for safer sideload installs.
- Added an explicit launcher icon so the APK has complete launcher metadata.

## Install note
- This installs as a clean app package. It avoids conflicts with any leftover `com.example.compilationmaker` install.
