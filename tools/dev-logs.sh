#!/usr/bin/env bash
# Both sides of a problem in one command: the phone's developer log and the
# PC's, merged by time.
#   tools/dev-logs.sh [phone serial] [-p package] [-n lines] [--since 30m]
# The phone's log comes from tools/phone-log.sh (Labs › Developer log on the
# phone); the PC's from `mimir devlog --raw`. Phone lines are in the
# phone's local time, PC lines in UTC: both are shown in this PC's local time.
# With no serial, only the PC's log is shown.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${ANDROID_SERIAL:-}"; pkg=""; lines=2000; since="30m"
while [ $# -gt 0 ]; do
    case "$1" in
        -p) pkg="-p $2"; shift 2 ;;
        -n) lines="$2"; shift 2 ;;
        --since) since="$2"; shift 2 ;;
        *) serial="$1"; shift ;;
    esac
done
work="$(mktemp -d "${TMPDIR:-/tmp}/dev-logs.XXXXXX")"
trap 'find "$work" -mindepth 1 -delete; rmdir "$work"' EXIT
prog="${MIMIR_BIN:-mimir}"
"$prog" devlog --since "$since" --raw --last 100000 > "$work/pc.jsonl" 2>/dev/null || true
if [ -n "$serial" ]; then
    # shellcheck disable=SC2086
    tools/phone-log.sh "$serial" -n "$lines" $pkg > "$work/phone.txt" 2>"$work/phone.err" \
        || { cat "$work/phone.err" >&2; : > "$work/phone.txt"; }
else
    : > "$work/phone.txt"
fi
python3 -I - "$work/pc.jsonl" "$work/phone.txt" <<'PY'
import json, sys, re, datetime
pc, phone = sys.argv[1], sys.argv[2]
rows = []
for l in open(pc, errors="replace"):
    try:
        v = json.loads(l)
    except ValueError:
        continue
    t = datetime.datetime.strptime(v["t"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=datetime.timezone.utc)
    t = t.astimezone().replace(tzinfo=None)
    rest = " ".join(f"{k}={v[k]}" for k in v if k not in ("t", "mono", "run", "cat", "ev"))
    rows.append((t, "PC   ", f'{v["cat"]:<8} {v["ev"]:<16} {rest}'))
stamp = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(.*)$")
for l in open(phone, errors="replace"):
    m = stamp.match(l.rstrip("\n"))
    if m:
        rows.append((datetime.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"), "PHONE", m.group(2)))
if not rows:
    print("Nothing in either log (is Labs › Developer log on, on both?).")
rows.sort(key=lambda r: r[0])
if phone and any(r[1] == "PHONE" for r in rows):
    first = min(r[0] for r in rows if r[1] == "PHONE")
    rows = [r for r in rows if r[0] >= first - datetime.timedelta(minutes=1)]
for t, side, text in rows:
    print(f"{t:%H:%M:%S.%f}"[:12], side, text[:300])
PY
