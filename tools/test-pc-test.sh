#!/usr/bin/env bash
# Checks tools/test-pc.sh without starting anything: names are policed, the folder is always under
# ~/.cache/mimir/test-pc-<name>/, and nothing points at the real home.
set -euo pipefail
cd "$(dirname "$0")"
real="$(getent passwd "$(id -un)" | cut -d: -f6)"
fail() { echo "test-pc-test: FAIL: $*" >&2; exit 1; }
for bad in 'Bad Name' '../x' '-x' '' 'a/b'; do
    if ./test-pc.sh dir "$bad" >/dev/null 2>&1; then fail "name '$bad' was accepted"; fi
done
[ "$(./test-pc.sh dir qa-1)" = "$real/.cache/mimir/test-pc-qa-1" ] || fail "folder is not under the test folder"
e="$(./test-pc.sh env qa-1)"
case "$e" in *"HOME=$real/.cache/mimir/test-pc-qa-1/home "*) ;; *) fail "HOME is not the test home: $e" ;; esac
case "$e" in *"XDG_RUNTIME_DIR=$real/.cache/mimir/test-pc-qa-1/run"*) ;; *) fail "runtime folder is not the test one: $e" ;; esac
if ./test-pc.sh bogus qa-1 >/dev/null 2>&1; then fail "an unknown command was accepted"; fi
echo "test-pc-test: ok"
