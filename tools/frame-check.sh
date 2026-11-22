#!/usr/bin/env bash
# The phone app's frame budget, measured on a real phone (redesign proposal, "The frame budget"):
# the journeys (cold start to chat, side menu, Settings, chat scrolled, rotation on tablets),
# each under 5% late frames and a 95th-percentile frame within the screen's budget (16 ms at
# 60 Hz, 11 at 90, 8 at 120). The result is kept
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
distrobox enter dev -- bash -lc "cd '$PWD/android' && export ANDROID_HOME=\$HOME/Android/Sdk ANDROID_SERIAL='$serial' && ./gradlew -g \$HOME/.cache/mimir/android-build -q --max-workers=12 :benchmark:connectedDevBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.odnwks.mimir.benchmark.FrameJourneys" || status=$?
# Nothing of the check is left on the phone.
adb -s "$serial" uninstall io.github.odnwks.mimir.dev >/dev/null 2>&1 || true
adb -s "$serial" uninstall io.github.odnwks.mimir.benchmark >/dev/null 2>&1 || true
[ "$status" = 0 ] || { echo "The frame check didn't run to the end (see above)."; exit 1; }
model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
mkdir -p "$cache/frames"
hz="$(adb -s "$serial" shell dumpsys display | grep -o 'fps=[0-9.]*' | head -1 | cut -d= -f2 | tr -d '\r')"
python3 -I - "$out" "$cache/frames/$tree" "$model" "${hz:-60}" <<'PY'
import glob, json, sys
out, record, model, hz = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
budget = 1000.0 / round(hz)          # 60 Hz 16.7 ms, 90 Hz 11.1, 120 Hz 8.3
files = glob.glob(f"{out}/**/*benchmarkData.json", recursive=True)
if not files:
    sys.exit("No frame results were written.")
ok = True
lines = [f"{model} at {round(hz)} Hz (frame budget {budget:.1f} ms), release code, {{}} repeats each"]
reps = 0
for b in json.load(open(files[0]))["benchmarks"]:
    dur = b.get("sampledMetrics", {}).get("frameDurationCpuMs", {})
    runs = dur.get("runs") or []
    frames = sorted(x for r in runs for x in r)
    if not frames:
        continue
    reps = max(reps, len(runs))
    pick = lambda q: frames[min(len(frames) - 1, int(q * len(frames)))]
    janky = 100.0 * sum(1 for x in frames if x > budget) / len(frames)
    p50, p95, p99 = pick(0.50), pick(0.95), pick(0.99)
    good = janky < 5.0 and p95 <= budget
    ok &= good
    lines.append(f"{b['name']}: {janky:.1f}% janky, p50 {p50:.1f} / p95 {p95:.1f} / p99 {p99:.1f} ms, {len(frames)} frames" + ("" if good else "  OVER BUDGET"))
lines[0] = lines[0].format(reps)
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
