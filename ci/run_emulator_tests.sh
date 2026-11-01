#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner. APKs in ./apks, results to ./results.
set -u
mkdir -p results
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
python3 ci/emulator_test.py results/full apks/debug.apk full || true
python3 ci/emulator_test.py results/release-smoke apks/PunaAI.apk smoke || true
ls -laR results | head -100
exit 0
