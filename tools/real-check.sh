#!/usr/bin/env bash
# Real checks: the core features against the real Claude Code on this PC
# (crates/ui/tests/real_claude.rs). Uses the owner's Claude sign-in and a
# little weekly use: about eight short prompts. Results go to the run log.
#   tools/real-check.sh                 every real check
#   tools/real-check.sh cf3_real_...    only these
# STATION_REAL_MODEL picks the model; set it to the smallest one's alias.
set -euo pipefail
cd "$(dirname "$0")/.."
claude="$(command -v claude)" || { echo "Claude Code is not installed."; exit 1; }
echo "Claude Code: $("$claude" --version)"
tools/cargo.sh test -p mimir --test real_claude --no-run --quiet
# Runs on SteamOS itself (not in the build box), like the real app.
t="$(ls -t "${CARGO_TARGET_DIR:-target}"/debug/deps/real_claude-* | grep -v '\.d$' | head -1)"

if [ $# -gt 0 ]; then
    checks=("$@")
else
    mapfile -t checks < <("$t" --list --ignored 2>/dev/null | sed -n 's/^\(cf[0-9]*_real_[a-z0-9_]*\): test$/\1/p')
fi

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
results=()
for c in "${checks[@]}"; do
    if STATION_REAL_CLAUDE="$claude" "$t" --ignored --exact "$c" --nocapture --test-threads 1 >"$out" 2>&1; then
        if why="$(grep -m1 '^COULD_NOT_RUN: ' "$out")"; then
            r=could_not_run
            echo "$c: could not run (${why#COULD_NOT_RUN: })"
        else
            r=passed
            echo "$c: passed"
        fi
    else
        r=failed
        echo "$c: FAILED"
        grep -E "panicked|assertion|left:|right:" "$out" | head -6 | sed 's/^/    /'
    fi
    grep -m1 '^REMOTE_URL: ' "$out" | sed 's/^/    /' || true
    results+=("$c=$r")
done

python3 tools/core_checks.py real-log "${results[@]}"

# The phone link: one pairing and session through n0's relays and address
# lookup (not a core-feature check; printed only).
if [ $# -eq 0 ]; then
    if tools/cargo.sh test -p station-link --test pair --quiet -- --ignored --exact real_relay_round_trip >"$out" 2>&1; then
        echo "phone link through the relays: passed"
    else
        echo "phone link through the relays: FAILED"
        grep -E "panicked|failed" "$out" | head -4 | sed 's/^/    /'
    fi
fi
