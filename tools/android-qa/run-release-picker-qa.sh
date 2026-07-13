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
adb -s "$serial" shell mkdir -p /sdcard/Download
for attempt in 1 2 3; do
  if adb -s "$serial" push "$fixture_a" /sdcard/Download/compilation_test_video_A.mp4 >/dev/null; then break; fi
  test "$attempt" -eq 3 && exit 1
  sleep 2
done
adb -s "$serial" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Download/compilation_test_video_A.mp4 >/dev/null
for _ in $(seq 1 20); do
  if adb -s "$serial" shell content query --uri content://media/external/video/media \
    --projection _display_name 2>/dev/null | grep -q compilation_test_video_A.mp4; then break; fi
  sleep 1
done
adb -s "$serial" install "$test_apk"
adb -s "$serial" shell am instrument -w -r \
  -e class "$test_class" \
  "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee "$artifacts/instrumentation.txt"
adb -s "$serial" logcat -d -t 1000 > "$artifacts/logcat.txt"
printf '%s\n' 'PASS deterministic picker handoff'
