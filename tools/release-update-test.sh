#!/usr/bin/env bash
# A real end-to-end check of Mímir's Flatpak source on this PC, with a tiny stand-in app
# (not the real build): everything lives in a throwaway Flatpak installation and a throwaway
# signing key, and is removed at the end. Never touches the installed Mímir.
#   tools/release-update-test.sh
# Steps: build a signed repo (0.01-test) and serve it on localhost; install the offline
# file (bundle); the app's own "Switch to automatic updates" code moves it onto the source;
# a 0.02-test is published; `flatpak update` and the app's own "Update now" code pick it up;
# older versions are pruned to the last three.
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d "$HOME/.cache/mimir/update-test.XXXXXX")"
port="$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
cleanup() {
    [ -z "${srv:-}" ] || kill -9 "$srv" 2>/dev/null || true
    gpgconf --homedir "$T/gpg" --kill gpg-agent >/dev/null 2>&1 || true
    rm -rf "$T"
}
trap cleanup EXIT
export FLATPAK_USER_DIR="$T/fp" MIMIR_GPG_HOME="$T/gpg"
R="$src/tools/release-repo.sh"
id=io.github.odnwks.Mimir
ok() { printf '  ok: %s\n' "$*"; }
bad() { printf '  FAILED: %s\n' "$*" >&2; exit 1; }
app() { # n: a stand-in app that prints its number
    rm -rf "$T/b"
    env -u FLATPAK_USER_DIR flatpak build-init "$T/b" "$id" org.freedesktop.Sdk org.freedesktop.Platform 26.08 >/dev/null
    mkdir -p "$T/b/files/bin"
    printf '#!/bin/sh\necho "version %s"\n' "$1" > "$T/b/files/bin/hello"
    chmod +x "$T/b/files/bin/hello"
    flatpak build-finish --command=hello "$T/b" >/dev/null
    "$R" export "$T/b" "$T/repo" >/dev/null 2>&1
}
echo "Flatpak source: real end-to-end test"
"$R" keygen >/dev/null
app 0.01-test
"$R" verify "$T/repo" >/dev/null && ok "signed repository, summary accepted by Flatpak"
mkdir -p "$T/srv"; ln -s "$T/repo" "$T/srv/flatpak"
python3 -I -m http.server "$port" --bind 127.0.0.1 --directory "$T/srv" >/dev/null 2>&1 & srv=$!
for _ in $(seq 20); do curl -sf "http://127.0.0.1:$port/flatpak/summary" >/dev/null && break; sleep 0.3; done
"$R" files "$T/repo" "$T/files" "http://127.0.0.1:$port/flatpak/" >/dev/null
"$R" bundle "$T/repo" "$T/Mimir-0.01-test.flatpak" >/dev/null
flatpak install --user -y --noninteractive "$T/Mimir-0.01-test.flatpak" >/dev/null 2>&1
[ "$(flatpak run --user "$id")" = "version 0.01-test" ] || bad "the file install did not run"
ok "installed from the offline file (0.01-test)"

export MIMIR_FLATPAK_VERBOSE=1
export MIMIR_REPO_FILE_URL="http://127.0.0.1:$port/flatpak-files/mimir.flatpakrepo"
mkdir -p "$T/srv/flatpak-files"; cp "$T/files/mimir.flatpakrepo" "$T/srv/flatpak-files/"
cd "$src"
# The test program is built in the build box but runs here: the box's own `flatpak` talks to
# the real installation and would ignore the throwaway one.
tools/cargo.sh test -p mimir-core --lib --no-run --message-format=json 2>/dev/null |
    python3 -I -c 'import json,sys
for l in sys.stdin:
    try: m = json.loads(l)
    except ValueError: continue
    if m.get("executable") and m.get("target", {}).get("name") == "mimir_core": print(m["executable"])' > "$T/exe"
exe="$(tail -1 "$T/exe")"; [ -x "$exe" ] || bad "the test program was not built"
"$exe" --ignored real_switch_from_a_file_to_a_source >"$T/t1.log" 2>&1 ||
    { tail -20 "$T/t1.log"; bad "the app's switch to automatic updates failed"; }
o="$(flatpak info --user --show-origin "$id")"; [ "$o" = mimir ] || { tail -15 "$T/t1.log"; bad "the app is not on the source ($o)"; }
[ "$(flatpak run --user "$id")" = "version 0.01-test" ] || bad "the app did not survive the switch"
ok "the app's own switch moved it onto the source, and it still runs"

app 0.02-test
[ "$(flatpak remote-ls --user --updates --columns=application mimir | head -1)" = "$id" ] ||
    bad "Flatpak does not see 0.02-test as an update"
ok "the software centre's check sees the new version"
"$exe" --ignored real_update_from_a_source --nocapture >"$T/t2.log" 2>&1 ||
    { tail -20 "$T/t2.log"; bad "the app's Update now failed"; }
[ "$(flatpak run --user "$id")" = "version 0.02-test" ] || bad "Update now did not install 0.02-test"
grep -h "progress seen" "$T/t2.log" || true
ok "the app's own Update now installed 0.02-test"

for v in 0.03-test 0.04-test 0.05-test; do app "$v"; done
n="$(ostree --repo="$T/repo" log "app/$id/x86_64/stable" | grep -c '^commit ')"
[ "$n" = 3 ] || bad "the repository kept $n versions, not 3"
"$R" verify "$T/repo" >/dev/null || bad "the pruned repository does not verify"
ok "only the last 3 versions are kept, and it still verifies"
flatpak update --user -y --noninteractive "$id" >/dev/null 2>&1
[ "$(flatpak run --user "$id")" = "version 0.05-test" ] || bad "a plain flatpak update did not reach 0.05-test"
ok "a plain flatpak update jumps across pruned versions"
echo "All real source tests passed."
