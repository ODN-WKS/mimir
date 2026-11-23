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
# stop ends this test PC's own hosts, and only those.
mkdir -p /tmp/test-pc-test-$$; cp "$(command -v sleep)" /tmp/test-pc-test-$$/mimir
m=/tmp/test-pc-test-$$/mimir
mine="$(./test-pc.sh dir stoptest-a)"; other="$(./test-pc.sh dir stoptest-b)"
# exec -a gives the process the look of a host: "mimir host t<hash>-<ms>".
( env XDG_STATE_HOME="$mine/home/.local/state" bash -c 'exec -a "$0 host t1a2b-111" '"$m"' 60' "$m" & echo $! > /tmp/test-pc-test-$$/a ) >/dev/null 2>&1
( env XDG_STATE_HOME="$other/home/.local/state" bash -c 'exec -a "$0 host t3c4d-222" '"$m"' 60' "$m" & echo $! > /tmp/test-pc-test-$$/b ) >/dev/null 2>&1
sleep 0.5
pa="$(cat /tmp/test-pc-test-$$/a)"; pb="$(cat /tmp/test-pc-test-$$/b)"
kill -0 "$pa" && kill -0 "$pb" || fail "the stand-in hosts did not start"
./test-pc.sh stop stoptest-a >/dev/null
sleep 0.3
if kill -0 "$pa" 2>/dev/null; then kill -9 "$pa" "$pb" 2>/dev/null; fail "stop left the test PC's own host running"; fi
kill -0 "$pb" 2>/dev/null || fail "stop ended another test PC's host"
kill -9 "$pb" 2>/dev/null || true
rm -rf /tmp/test-pc-test-$$
echo "test-pc-test: ok"
