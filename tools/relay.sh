#!/usr/bin/env bash
# For a Claude session Mímir runs, as its last step: carry this task on in a new session that
# starts from the project's hand-off. It happens once the current run has ended; the chat stays
# the same. Prints "Fresh start queued for <task>" or the plain reason (exit 1).
#   tools/relay.sh
# The task is the one Mímir set in MIMIR_TASK_ID. $MIMIR_BIN picks the program; without one
# here, the Flatpak's copy is used.
set -euo pipefail
bin="${MIMIR_BIN:-$HOME/.local/bin/mimir}"
[ -x "$bin" ] || bin="$(command -v mimir || true)"
[ -n "$bin" ] && exec "$bin" fresh-start
# Installed as a Flatpak: the program lives inside it.
if command -v flatpak >/dev/null && flatpak info io.github.odnwks.Mimir >/dev/null 2>&1; then
  exec flatpak run --command=mimir io.github.odnwks.Mimir fresh-start
fi
echo "Mímir isn't installed here, so there is no fresh start." >&2; exit 1
