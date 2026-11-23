#!/usr/bin/env bash
# One command: the dev debug app on an emulated phone of this PC (mimir-emu) opens to its home
# screen with no crash. Usage: tools/emu-check.sh <folder for the screenshot> [n]
# Builds first, then starts the emulator, installs the dev debug app, opens it, waits for the home
# screen, takes home.png, looks in the log for a crash (Java or native) or a restart of the app,
# and stops the emulator only if this run started it. Exit 0 only with no crash.
set -uo pipefail
cd "$(dirname "$0")/.."
out=${1:?usage: tools/emu-check.sh <out folder> [n]}
n=${2:-1}
mkdir -p "$out"
ADB=$HOME/.local/bin/adb
pkg=io.github.odnwks.mimir.dev
unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
if [ -z "${EMU_APK:-}" ]; then
    distrobox enter dev -- bash -lc "cd '$PWD/android' && ./gradlew -g \$HOME/.cache/mimir/android-build --max-workers=5 assembleDevDebug" >"$out/build.log" 2>&1 \
        || { echo "emu-check: the build failed (see $out/build.log)" >&2; exit 1; }
    apk=$(ls -t android/app/build/outputs/apk/dev/debug/*.apk | head -1)
else
    apk=$EMU_APK
fi
# Only an emulator this run started is stopped by it.
was_running=0
~/.local/bin/mimir-emu list | grep -q "emulator-$((5580 + 2 * n))" && was_running=1
serial=$(~/.local/bin/mimir-emu start "$n") || { echo "emu-check: the emulator didn't start" >&2; exit 1; }
[ "$was_running" = 0 ] && trap '~/.local/bin/mimir-emu stop "$n" >/dev/null 2>&1' EXIT
a="$ADB -s $serial"
status=1
$a logcat -c
$a install -r "$apk" >"$out/install.log" 2>&1 || { echo "emu-check: install failed (see $out/install.log)" >&2; exit 1; }
$a shell am start -W -n "$pkg/io.github.odnwks.mimir.MainActivity" >"$out/start.log" 2>&1
up=0
for _ in $(seq 1 45); do
    sleep 2
    if $a shell pidof "$pkg" >/dev/null 2>&1 && $a shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$pkg"; then up=1; break; fi
done
pid1=$($a shell pidof "$pkg" | tr -d '\r')
sleep 8
pid2=$($a shell pidof "$pkg" | tr -d '\r')
$a exec-out screencap -p >"$out/home.png"
$a logcat -d >"$out/logcat.txt" 2>&1
if grep -A3 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "$pkg"; then
    echo "emu-check: the app crashed (see $out/logcat.txt)" >&2
elif grep -E "Fatal signal|ANR in $pkg|>>> $pkg <<<" "$out/logcat.txt" | grep -q .; then
    echo "emu-check: the app crashed natively or hung (see $out/logcat.txt)" >&2
elif [ "$up" != 1 ] || [ -z "$pid2" ] || [ "$pid1" != "$pid2" ]; then
    echo "emu-check: the app isn't on screen or restarted (see $out/logcat.txt)" >&2
else
    echo "emu-check: ok, no crash; screenshot $out/home.png"
    status=0
fi
exit $status
