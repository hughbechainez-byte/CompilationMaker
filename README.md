# CompilationMaker (Barebones Android Build)

This repository contains a starter Android app that:

1. Let users pick a video.
2. Scans the corner of frames with OCR.
3. Detects number changes.
4. Cuts `10s before -> 30s after` each detected change.
5. Concatenates those clips into a single compilation.
6. Exports to selected quality/format and saves it into `Movies/CompilationMaker`.

## Build and run on a Pixel using WSL (Debian)

From Windows PowerShell, open Debian in WSL and use the workspace mount:

```bash
cd /mnt/c/Users/blowb/Documents/compilationmaker
./gradlew assembleDebug
```

Install and launch on connected Pixel:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.compilationmaker/.MainActivity
```

If `./gradlew` is missing in WSL, run from Windows Android Studio terminal or make the wrapper executable first:

```bash
chmod +x gradlew
```

