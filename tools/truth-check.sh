#!/usr/bin/env bash
# The truth check (release gate): about 100 items against the PC's free model, through Mímir's real
# instructions. Local only; low priority. Usage:
#   tools/truth-check.sh                          new rules (the working tree)
#   tools/truth-check.sh --old-rules-ref HEAD     the rules at that git ref, for comparing
#   tools/truth-check.sh --model NAME --limit N   a model; only the first N items of each kind
set -euo pipefail
cd "$(dirname "$0")/.."
exec nice -n 19 ionice -c 3 python3 -I tools/truth_check.py "$@"
