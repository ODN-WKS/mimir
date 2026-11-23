#!/usr/bin/env bash
# One command: the dev debug app on an emulated phone of this PC (mimir-emu 1) opens to its home
# screen with no crash. Usage: tools/emu-check.sh <folder for the screenshot> [n]
# Starts the emulator, builds and installs the dev debug app, opens it, waits for the home screen,
# takes home.png, looks for a crash of the app in the log, stops the emulator. Exit 0 only with no crash.
set -uo pipefail
cd "$(dirname "$0")/.."
out=${1:?usage: tools/emu-check.sh <out folder> [n]}
n=${2:-1}
mkdir -p "$out"
ADB=$HOME/.local/bin/adb
pkg=io.github.odnwks.mimir.dev
unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
status=1
serial=$(~/.local/bin/mimir-emu start "$n") || { echo "emu-check: the emulator didn't start" >&2; exit 1; }
trap '~/.local/bin/mimir-emu stop "$n" >/dev/null 2>&1' EXIT
a="$ADB -s $serial"
if [ -z "${EMU_APK:-}" ]; then
    distrobox enter dev -- bash -lc "cd '$PWD/android' && ./gradlew -g \$HOME/.cache/mimir/android-build --max-workers=5 assembleDevDebug" >"$out/build.log" 2>&1 \
        || { echo "emu-check: the build failed (see $out/build.log)" >&2; exit 1; }
    apk=$(ls -t android/app/build/outputs/apk/dev/debug/*.apk | head -1)
else
    apk=$EMU_APK
fi
$a logcat -c
$a install -r "$apk" >"$out/install.log" 2>&1 || { echo "emu-check: install failed (see $out/install.log)" >&2; exit 1; }
$a shell am start -W -n "$pkg/io.github.odnwks.mimir.MainActivity" >"$out/start.log" 2>&1
# Wait for the app to be on screen and its process alive (up to 90 s).
up=0
for _ in $(seq 1 45); do
    sleep 2
    if $a shell pidof "$pkg" >/dev/null 2>&1 && $a shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$pkg"; then up=1; break; fi
done
sleep 5
$a exec-out screencap -p >"$out/home.png"
$a logcat -d >"$out/logcat.txt" 2>&1
if grep -q "FATAL EXCEPTION" "$out/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "$pkg"; then
    echo "emu-check: the app crashed (see $out/logcat.txt)" >&2
elif [ "$up" != 1 ] || ! $a shell pidof "$pkg" >/dev/null 2>&1; then
    echo "emu-check: the app isn't on screen (see $out/logcat.txt)" >&2
else
    echo "emu-check: ok, no crash; screenshot $out/home.png"
    status=0
fi
exit $status
