#!/usr/bin/env bash
# A throw-away "test PC" for phone checks: its own data folders, runtime folder, control socket and
# systemd unit, so the real Mímir (and its data) is never touched. Release builds only start inside a
# unit with Delegate=yes, and the command line only talks to a service from "$HOME/.local/bin/mimir"
# of the SAME home: this script sets both up.
#
#   tools/test-pc.sh start <name> [--ollama URL] [--env KEY=VALUE]... [--real-claude]
#                                                 start it (own unit mimir-test-pc-<name>). --real-claude: its tasks
#                                                 run the real Claude Code with the real home and sign-in, on a small
#                                                 model (ANTHROPIC_MODEL, default claude-haiku-5-5), and project work uses Claude (not free models), through a wrapper
#                                                 written into its folder (MIMIR_CLAUDE). To get a permission prompt
#                                                 with the owner's settings, add an "ask Bash" rule in the test project.
#   tools/test-pc.sh pair  <name> [mine|NAME]    make a pairing code and answer the PC's questions: the phone
#                                                 types the printed code; "mine" (default) is the owner's own
#                                                 phone, a first name is someone else's
#   tools/test-pc.sh stop  <name> [--clean]      stop it and its hosts (and with --clean delete its folder)
#   tools/test-pc.sh env   <name>                print the variables to run the CLI against it:
#                                                 eval "$(tools/test-pc.sh env qa)"; $HOME/.local/bin/mimir status
#   tools/test-pc.sh dir   <name>                print its folder (service.log, pair.out live there)
#
# The program is $MIMIR_BIN (default: the release build in /tmp/mimir-build/target/release/mimir).
# Folders live under ~/.cache/mimir/test-pc-<name>/. Names: letters, digits and dashes only.
set -euo pipefail

REAL_HOME="${REAL_HOME:-$(getent passwd "$(id -un)" | cut -d: -f6)}"
cmd="${1:-}"
name="${2:-}"
[ -n "$cmd" ] && [ -n "$name" ] || { sed -n 2,22p "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift 2
if ! [[ "$name" =~ ^[a-z0-9][a-z0-9-]{0,30}$ ]]; then
    echo "test-pc: the name may only hold lowercase letters, digits and dashes (got '$name')" >&2
    exit 2
fi
D="$REAL_HOME/.cache/mimir/test-pc-$name"
H="$D/home"
UNIT="mimir-test-pc-$name"
# Never touch the real home or its data: the folders must sit under the test folder.
case "$H" in "$REAL_HOME/.cache/mimir/test-pc-"*) ;; *) echo "test-pc: refusing to use $H" >&2; exit 2 ;; esac
[ "$H" != "$REAL_HOME" ] || { echo "test-pc: refusing to use the real home" >&2; exit 2; }

envvars() {
    echo "export HOME=$H XDG_CONFIG_HOME=$H/.config XDG_DATA_HOME=$H/.local/share XDG_STATE_HOME=$H/.local/state XDG_CACHE_HOME=$H/.cache XDG_RUNTIME_DIR=$D/run"
}
with_env() { env HOME="$H" XDG_CONFIG_HOME="$H/.config" XDG_DATA_HOME="$H/.local/share" XDG_STATE_HOME="$H/.local/state" XDG_CACHE_HOME="$H/.cache" XDG_RUNTIME_DIR="$D/run" PATH=/usr/bin:/bin LANG=C.UTF-8 "$@"; }

case "$cmd" in
env) envvars ;;
dir) echo "$D" ;;
start)
    ollama="${MIMIR_OLLAMA:-http://127.0.0.1:11434}"
    extra=()
    real_claude=0
    while [ $# -gt 0 ]; do
        case "$1" in
        --ollama) ollama="$2"; shift 2 ;;
        --env) extra+=(--setenv="$2"); shift 2 ;;
        --real-claude) real_claude=1; shift ;;
        *) echo "test-pc: unknown option $1" >&2; exit 2 ;;
        esac
    done
    bin="${MIMIR_BIN:-/tmp/mimir-build/target/release/mimir}"
    [ -x "$bin" ] || { echo "test-pc: no program at $bin (build it: tools/cargo.sh build --release -p mimir-ui --bin mimir)" >&2; exit 1; }
    if systemctl --user is-active --quiet "$UNIT"; then echo "test-pc: $UNIT is already running"; exit 1; fi
    mkdir -p "$H/.config" "$H/.local/share" "$H/.local/state" "$H/.cache" "$H/.local/bin" "$D/run"
    chmod 700 "$D/run"
    if [ "$real_claude" = 1 ]; then
        real_claude_bin="$(command -v claude 2>/dev/null || true)"
        [ -n "$real_claude_bin" ] || real_claude_bin="$REAL_HOME/.local/bin/claude"
        [ -x "$real_claude_bin" ] || { echo "test-pc: no Claude Code found for --real-claude" >&2; exit 1; }
        {
            echo '#!/bin/bash'
            echo '# Runs the real Claude Code (the real home and sign-in) on a small model, for this test PC only.'
            echo 'unset XDG_CONFIG_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_CACHE_HOME'
            printf 'export HOME=%q XDG_RUNTIME_DIR=/run/user/%s\n' "$REAL_HOME" "$(id -u)"
            printf 'export PATH=%q\n' "$REAL_HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
            echo 'export ANTHROPIC_MODEL="${ANTHROPIC_MODEL:-claude-haiku-5-5}"'
            printf 'exec %q "$@"\n' "$real_claude_bin"
        } > "$D/claude-real"
        chmod 700 "$D/claude-real"
        extra+=(--setenv=MIMIR_CLAUDE="$D/claude-real")
        # Project work must really use Claude (a fresh service starts on free models only): the
        # setting is written before the service starts, only where this test PC has none yet.
        conf="$H/.config/mimir/settings.json"
        mkdir -p "$H/.config/mimir"
        [ -e "$conf" ] || printf '{"project_engine":"claude"}\n' > "$conf"
    fi
    cp "$bin" "$D/mimir-bin"
    cp "$bin" "$H/.local/bin/mimir"
    systemd-run --user --quiet --unit="$UNIT" -p Delegate=yes -p Nice=10 \
        --setenv=HOME="$H" --setenv=XDG_CONFIG_HOME="$H/.config" --setenv=XDG_DATA_HOME="$H/.local/share" \
        --setenv=XDG_STATE_HOME="$H/.local/state" --setenv=XDG_CACHE_HOME="$H/.cache" --setenv=XDG_RUNTIME_DIR="$D/run" \
        --setenv=MIMIR_OLLAMA="$ollama" --setenv=LANG=C.UTF-8 --setenv=MIMIR_NO_POPUPS=1 "${extra[@]}" \
        -p StandardOutput=append:"$D/service.log" -p StandardError=append:"$D/service.log" \
        "$D/mimir-bin" serve
    for _ in $(seq 1 30); do
        with_env "$H/.local/bin/mimir" status >/dev/null 2>&1 && { echo "test-pc: $name is running (unit $UNIT, folder $D)"; exit 0; }
        sleep 1
    done
    echo "test-pc: $name did not come up; see $D/service.log" >&2
    exit 1
    ;;
pair)
    who="${1:-mine}"
    systemctl --user is-active --quiet "$UNIT" || { echo "test-pc: $name is not running (start it first)" >&2; exit 1; }
    pkill -f "$H/.local/bin/mimir pair" 2>/dev/null || true
    rm -f "$D/pin"; mkfifo "$D/pin"
    : > "$D/pair.out"
    # The answers are queued up front: "yes, the words match", then whose phone it is.
    # (stdin is opened read-write so it never sees an end of input.)
    # (the whole background list is cut off from this script's output, so `| tail` never waits for it)
    ( cd "$D" && with_env setsid nohup "$H/.local/bin/mimir" pair 0<>"$D/pin" >"$D/pair.out" 2>&1 ) >/dev/null 2>&1 </dev/null &
    for _ in $(seq 1 20); do grep -q '^Pairing code:' "$D/pair.out" && break; sleep 0.5; done
    grep -q '^Pairing code:' "$D/pair.out" || { echo "test-pc: no code came out; see $D/pair.out" >&2; exit 1; }
    printf 'y\n%s\n' "$who" > "$D/pin"
    grep -o 'mimir1:[^ ]*' "$D/pair.out" | head -1
    echo "test-pc: type that code on the phone (Connect your PC > Type the code instead). It is good for 10 minutes; progress: $D/pair.out" >&2
    ;;
stop)
    pkill -f "$H/.local/bin/mimir pair" 2>/dev/null || true
    systemctl --user stop "$UNIT" 2>/dev/null || true
    # A finished task's host waits to be collected; a stopped test PC never collects, so end the
    # hosts of this test PC (only processes whose state folder is under this test PC's folder).
    for pid in $(pgrep -f 'mimir.* host t[0-9a-f]+-[0-9]+' 2>/dev/null || true); do
        [ "$pid" != "$$" ] || continue
        if tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null | grep -qxF "XDG_STATE_HOME=$H/.local/state"; then
            kill -9 -- "-$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true
        fi
    done
    if [ "${1:-}" = "--clean" ]; then rm -rf -- "$D"; echo "test-pc: $name stopped and its folder deleted"; else echo "test-pc: $name stopped (folder kept: $D)"; fi
    ;;
*) echo "test-pc: unknown command $cmd" >&2; exit 2 ;;
esac
