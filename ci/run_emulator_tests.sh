#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner. APKs are in ./apks, results go to ./results.
set -u
API="$1"
mkdir -p results
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
if [ -f apks/debug.apk ]; then
  python3 ci/emulator_test.py "results/api${API}-debug-full" apks/debug.apk full || true
fi
if [ -f apks/release.apk ]; then
  if [ -f apks/previous-latest.apk ]; then
    python3 ci/emulator_test.py "results/api${API}-release-upgrade" apks/release.apk smoke apks/previous-latest.apk || true
  fi
  python3 ci/emulator_test.py "results/api${API}-release-smoke" apks/release.apk smoke || true
fi
ls -laR results
exit 0
