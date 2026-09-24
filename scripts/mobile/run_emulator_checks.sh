#!/usr/bin/env bash
set -euo pipefail
device="$1"
size="$2"
density="$3"
out="artifacts/emulator/$device"
mkdir -p "$out"
capture_failure() {
  adb logcat -d > "$out/logcat.txt" 2>&1 || true
  adb exec-out screencap -p > "$out/final-screen.png" || true
  adb shell uiautomator dump /sdcard/akagi-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/akagi-window.xml "$out/window.xml" >/dev/null 2>&1 || true
  adb pull /sdcard/Android/data/org.akagi.mobile.debug/files/screenshots "$out/screenshots" >/dev/null 2>&1 || true
  adb pull /sdcard/Android/data/org.akagi.mobile.debug/files/measurements "$out/measurements" >/dev/null 2>&1 || true
}
trap capture_failure EXIT
adb shell wm size "$size"
adb shell wm density "$density"
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell input keyevent 82
adb logcat -c
app=$(find artifacts/apks -name app-debug.apk -print -quit)
tests=$(find artifacts/apks -name app-debug-androidTest.apk -print -quit)
test -n "$app" && test -n "$tests"
adb install -r -t "$app"
adb install -r -t "$tests"
adb shell getprop > "$out/device-properties.txt"
adb shell am instrument -w -r org.akagi.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt"
if ! grep -Eq 'OK \([0-9]+ tests?\)' "$out/instrumentation.txt"; then
  echo 'Instrumentation did not report a passing test suite.' >&2
  exit 1
fi
# Random interactions use the isolated game fixture, never a signed-in account.
adb shell am force-stop org.akagi.mobile.debug
adb shell am start -n org.akagi.mobile.debug/org.akagi.mobile.MainActivity --es akagi.debug.fixture table
adb shell monkey -p org.akagi.mobile.debug -s 42716 --throttle 80 --pct-syskeys 0 --pct-appswitch 0 250 | tee "$out/interaction-stress.txt"
if grep -Eq 'CRASH|ANR|Monkey aborted' "$out/interaction-stress.txt"; then exit 1; fi
# Verify the actual public game loads in the production Activity too.
adb shell am force-stop org.akagi.mobile.debug
adb shell am start -n org.akagi.mobile.debug/org.akagi.mobile.MainActivity
sleep 35
adb exec-out screencap -p > "$out/public-game.png"
adb shell dumpsys meminfo org.akagi.mobile.debug > "$out/memory.txt"
adb shell dumpsys gfxinfo org.akagi.mobile.debug > "$out/rendering.txt"
adb shell pidof org.akagi.mobile.debug > "$out/live-process.txt"
echo "PASS: $device Android 16 instrumentation and interaction stress completed."
