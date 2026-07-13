#!/usr/bin/env bash
set -euo pipefail

release_apk="$1"
test_apk="$2"
fixture_a="$3"
artifacts="$4"
serial="${ANDROID_SERIAL:-emulator-5554}"
package="com.hughbechainez.compilationmaker"
test_package="${package}.test"
test_class="com.example.compilationmaker.VideoPickerHandoffTest"

mkdir -p "$artifacts"
test -f "$release_apk"
test -f "$test_apk"
test -f "$fixture_a"

sha256sum "$release_apk" > "$artifacts/apk-sha256.txt"
sha256sum "$fixture_a" > "$artifacts/fixture-sha256.txt"
adb -s "$serial" install -r "$release_apk"
adb -s "$serial" shell pm clear "$package" >/dev/null
adb -s "$serial" push "$fixture_a" /sdcard/Download/compilation_test_video_A.mp4 >/dev/null
adb -s "$serial" install "$test_apk"
adb -s "$serial" shell am instrument -w -r \
  -e class "$test_class" \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee "$artifacts/instrumentation.txt"
adb -s "$serial" logcat -d -t 1000 > "$artifacts/logcat.txt"
printf '%s\n' 'PASS deterministic picker handoff'
