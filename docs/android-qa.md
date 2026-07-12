# Android emulator QA

The project now has a repeatable Android QA path using the Test Android Apps workflow and Windows-native Android Emulator tooling.

## Local machine

Installed components:

- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- Android command-line tools
- Platform Tools / ADB 37
- Emulator 36.6.11
- API 35 Google APIs x86_64 system image
- Build Tools 35.0.0
- AVD: `CompilationMaker_API35`
- WHPX: installed and usable

Start the AVD:

```powershell
$env:ANDROID_SDK_ROOT="$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" @CompilationMaker_API35 -no-boot-anim -no-audio -gpu auto -memory 4096
```

Run the local fixture test:

```powershell
pwsh -File .\tools\android-qa\run-release-qa.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -VideoAPath "$env:USERPROFILE\Desktop\comp test vids\compilation test video A.mp4" `
  -VideoBPath "$env:USERPROFILE\Desktop\comp test vids\compilation test video B.mp4"
```

The default automated fixture profile is `1-minute checkpoints`. This keeps Video A within the app's bounded scan budget on an emulator while exercising OCR confirmation, clip planning, export, verification, and lifecycle handling. Use `-ScanProfile 'Fast change-map (500ms)'` for a longer performance run on a faster target.

The script uses ADB, UI-tree-derived coordinates, media staging, Logcat polling, and artifact capture. It does not use screenshot coordinates guessed by eye.

## GitHub release QA

`.github/workflows/release-android-qa.yml` runs on every published GitHub release and can also be started manually. It provisions an API 35 emulator, downloads the release APK and persistent Video A/B fixture assets, runs the script, and uploads `qa-artifacts` for review.

WSL Debian is useful for Gradle/FFmpeg/report-processing scripts, but the emulator and ADB server remain Windows-native. This avoids nested virtualization and keeps WHPX available. WSL can invoke Windows tools through `/mnt/c/...` or `adb.exe` when Windows interop is enabled.
