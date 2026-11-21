#!/usr/bin/env bash
# The phone app's frame budget, measured on a real phone (redesign proposal, "The frame budget"):
# five journeys (tabs, keyboard and send, a task starting, a task opened and closed, Updates
# scrolled), each under 5% late frames with a 99th percentile under 16 ms. The result is kept
# for this exact app code, and the release gate (tools/release.sh --draft) reads it.
#   tools/frame-check.sh <adb serial>     e.g. the S8; run it on the S25 too before a release
# It installs the check build ("Mímir dev") with example data, and takes it off after.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Give the adb serial of the phone (adb devices).}"
cache="${STATION_RELEASE_CACHE:-$HOME/.cache/mimir-checks}"
tree="$(git rev-parse HEAD:android)"
[ -z "$(git status --porcelain -- android)" ] ||
    { echo "The phone app has changes that aren't committed; commit them first, so the result names the code it measured."; exit 1; }
out="android/app/build/benchmark-module/outputs/connected_android_test_additional_output"
rm -rf "$out"
status=0
distrobox enter dev -- bash -lc "cd '$PWD/android' && export ANDROID_HOME=\$HOME/Android/Sdk ANDROID_SERIAL='$serial' && ./gradlew -q --no-daemon --max-workers=6 :benchmark:connectedDevBenchmarkAndroidTest" || status=$?
# Nothing of the check is left on the phone.
adb -s "$serial" uninstall io.github.odnwks.mimir.dev >/dev/null 2>&1 || true
adb -s "$serial" uninstall io.github.odnwks.mimir.benchmark >/dev/null 2>&1 || true
[ "$status" = 0 ] || { echo "The frame check didn't run to the end (see above)."; exit 1; }
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
mkdir -p "$cache/frames"
python3 -I - "$out" "$cache/frames/$tree" "$model" <<'PY'
import glob, json, sys
out, record, model = sys.argv[1:4]
files = glob.glob(f"{out}/**/*benchmarkData.json", recursive=True)
if not files:
    sys.exit("No frame results were written.")
ok = True
lines = []
for b in json.load(open(files[0]))["benchmarks"]:
    m = b.get("sampledMetrics", {})
    dur = m.get("frameDurationCpuMs", {})
    over = m.get("frameOverrunMs", {})
    p99 = dur.get("P99", 0.0)
    # Under 5% late frames: the 95th percentile of how late frames were is not above zero.
    late = over.get("P95") if over else None
    good = p99 < 16 and (late is None or late <= 0)
    ok &= good
    lines.append(f"{b['name']}: 99th percentile {p99:.1f} ms" + (f", 95th late {late:.1f} ms" if late is not None else "") + ("" if good else "  OVER BUDGET"))
lines.append(f"{model}: " + ("pass" if ok else "fail"))
print("\n".join(lines))
# One line per phone; a later run on the same phone replaces its line.
try:
    kept = [l for l in open(record).read().splitlines() if not l.startswith(model + ": ")]
except FileNotFoundError:
    kept = []
open(record, "w").write("\n".join(kept + [lines[-1]]) + "\n")
sys.exit(0 if ok else 1)
PY
