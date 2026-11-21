#!/usr/bin/env bash
# Pulls the phone app's developer log (Labs › Developer log) and shows it.
#   tools/phone-log.sh [serial] [-g pattern] [-p package] [-n lines] [-i]
# -i shows only the important log (errors, crashes, stalls, failed lookups and models; kept 30 days).
# Files land in ~/.cache/mimir-checks/phone-logs/<serial>/ (the old ones there are replaced).
# Package: the real app by default; the check build is io.github.odnwks.mimir.dev.
set -euo pipefail
serial="${ANDROID_SERIAL:-}"; pattern=""; pkg="io.github.odnwks.mimir"; lines=200; files="devlog-*.txt"
while [ $# -gt 0 ]; do
    case "$1" in
        -g) pattern="$2"; shift 2 ;;
        -p) pkg="$2"; shift 2 ;;
        -n) lines="$2"; shift 2 ;;
        -i) files="important-*.txt"; shift ;;
        *) serial="$1"; shift ;;
    esac
done
[ -n "$serial" ] || { echo "Give the phone's serial (adb devices)"; exit 2; }
out="$HOME/.cache/mimir-checks/phone-logs/$serial"
find "$out" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$out"
adb -s "$serial" pull "/sdcard/Android/data/$pkg/files/devlog/." "$out/" >/dev/null 2>&1 \
    || { echo "No developer log on $serial for $pkg (is Labs › Developer log on, and has the app run?)"; exit 1; }
if [ -n "$pattern" ]; then cat "$out"/$files | grep -a -e "$pattern" || true
else cat "$out"/$files | tail -n "$lines"; fi
