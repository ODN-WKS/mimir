#!/usr/bin/env bash
# For a Claude session Mímir runs, as its last step: carry this task on in a new session that
# starts from the project's hand-off. It happens once the current run has ended; the chat stays
# the same. Prints "Fresh start queued for <task>" or the plain reason (exit 1).
#   tools/relay.sh
# The task is the one Mímir set in MIMIR_TASK_ID. $MIMIR_BIN picks the program.
set -euo pipefail
bin="${MIMIR_BIN:-$HOME/.local/bin/mimir}"
[ -x "$bin" ] || bin="$(command -v mimir || true)"
[ -n "$bin" ] || { echo "Mímir isn't installed here, so there is no fresh start." >&2; exit 1; }
exec "$bin" fresh-start
