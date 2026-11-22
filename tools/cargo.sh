#!/usr/bin/env bash
# Runs cargo inside the Debian 13 build box, so the program links against
# an older glibc than SteamOS's and runs on this PC and older systems.
#   tools/cargo.sh build --release
set -euo pipefail
cd "$(dirname "$0")/.."
# The box name can be set per machine in ~/.config/mimir/build-box.
box=$(cat "$HOME/.config/mimir/build-box" 2>/dev/null || echo mimir)
# Builds go to RAM (/tmp, which is in memory here) to spare the SSDs (owner, 2026-11-21); no
# incremental files, which are most of the writes. A reboot just means one
# full rebuild.
export CARGO_INCREMENTAL=0
# Memory is shared (32 GB for all of /tmp): when it is over 70 % full, drop build
# leftovers not touched for 3 hours (old test programs pile up; cargo rebuilds what it needs).
use=$(df --output=pcent /tmp 2>/dev/null | tail -1 | tr -dc 0-9)
if [ "${use:-0}" -gt 70 ] && [ -d /tmp/mimir-build ]; then
    find /tmp/mimir-build -path '*/deps/*' -type f -mmin +180 -delete 2>/dev/null || true
fi
if [ "${CONTAINER_ID:-}" = "$box" ]; then
    export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/mimir-build/target}"
    exec cargo "$@"
fi
# grep reads the whole list: with -q it can stop early and fail the pipe.
if ! distrobox list 2>/dev/null | grep "| $box " >/dev/null; then
    echo "The build box is missing. Make it with: tools/make-build-box.sh" >&2
    exit 1
fi
# A build folder set by the caller (the release worktree has its own) goes
# with it into the box.
exec distrobox enter "$box" -- bash -c \
    '. "$HOME/.cargo/env" 2>/dev/null || export PATH="$HOME/.cargo/bin:$PATH"; cd "$1" && if [ -n "$2" ]; then export CARGO_TARGET_DIR="$2"; fi && export CARGO_INCREMENTAL=0 && shift 2 && exec cargo "$@"' \
    _ "$PWD" "${CARGO_TARGET_DIR:-/tmp/mimir-build/target}" "$@"
