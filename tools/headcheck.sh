#!/usr/bin/env bash
# Proves HEAD builds on its own from a clean clone (PC release binary and the
# phone's dev debug app), then removes the clone and every build folder it made.
#   tools/headcheck.sh [name]      name: who is checking (keeps clones apart)
set -euo pipefail
cd "$(dirname "$0")/.."
repo=$(pwd -P)
name="${1:-check}"
clone="$HOME/.cache/mimir/headcheck-$name"
unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
rm -rf "$clone"
git clone -q --local "$repo" "$clone"
hash=$(cd "$clone" && pwd -P | sha256sum | cut -c1-12)
cleanup() {
    cd "$HOME"
    rm -rf "$clone" "/tmp/mimir-build/headcheck-$name" "/tmp/mimir-build/android-target/$hash"
    # The phone build's folder in memory is named after the clone's path.
    for d in /tmp/mimir-build/android-out/*; do
        [ -e "$d/.headcheck-$name" ] && rm -rf "$d"
    done
    return 0
}
trap cleanup EXIT
cp android/local.properties "$clone/android/" 2>/dev/null || true
echo "PC: release build of $(git -C "$clone" log --oneline -1)"
(cd "$clone" && CARGO_TARGET_DIR=/tmp/mimir-build/target tools/cargo.sh build -q --release -p mimir-ui --bin mimir)
echo "PC: built"
echo "Phone: dev debug build"
distrobox enter dev -- bash -lc "cd '$clone/android' && export ANDROID_HOME=\$HOME/Android/Sdk && ./gradlew -q -g \$HOME/.cache/mimir/android-build --max-workers=8 assembleDevDebug; s=\$?; out=\$(readlink -f app/build)/..; touch \"\$out/.headcheck-$name\" 2>/dev/null; exit \$s"
echo "Phone: built"
echo "HEAD builds from a clean clone."
