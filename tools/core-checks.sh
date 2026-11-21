#!/usr/bin/env bash
# Keep it working: the core-feature checks and their run log.
#   tools/core-checks.sh window               every test under nextest; cf* results to the run log
#   tools/core-checks.sh real                 the checks against the real Claude Code (tools/real-check.sh)
#   tools/core-checks.sh prove [check...]     each proof patch must make its check fail (all with no names)
#   tools/core-checks.sh prove --changed      only checks whose test or patch changed since their last proof
#   tools/core-checks.sh prove --changed-files  also checks whose listed source files changed
#   tools/core-checks.sh false "why" [--run <time>]  marks a run's failure as false (newest run by default)
# The run log: ~/.local/share/keep-it-working/mimir.jsonl
# Design: docs/designs/control-center/design_draft.md
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
py() { python3 -I "$root/tools/core_checks.py" "$@"; }
cache="$HOME/.cache/mimir-checks"

# cargo in the build box, in folder $1 with build folder $2.
box_cargo() {
    local dir="$1" target="$2" box
    shift 2
    # The same box tools/cargo.sh uses (named per machine in ~/.config/mimir/build-box).
    box=$(cat "$HOME/.config/mimir/build-box" 2>/dev/null || echo mimir)
    if [ "${CONTAINER_ID:-}" = "$box" ]; then
        (cd "$dir" && CARGO_TARGET_DIR="$target" cargo "$@")
    else
        distrobox enter "$box" -- bash -c \
            '. "$HOME/.cargo/env" 2>/dev/null || export PATH="$HOME/.cargo/bin:$PATH"; cd "$1" && export CARGO_TARGET_DIR="$2" && shift 2 && exec cargo "$@"' \
            _ "$dir" "$target" "$@"
    fi
}

window() {
    # nextest writes its report under the build folder (tools/cargo.sh's, or
    # CARGO_TARGET_DIR); the workspace's own target/ is checked too.
    local tgt="${CARGO_TARGET_DIR:-/tmp/mimir-build/target}" status=0 junit="" j
    local places=("$tgt/nextest/default/junit.xml" "$root/target/nextest/default/junit.xml")
    rm -f "${places[@]}"
    tools/cargo.sh nextest run --workspace --no-fail-fast || status=$?
    for j in "${places[@]}"; do
        if [ -f "$j" ]; then junit="$j"; break; fi
    done
    if [ -n "$junit" ]; then
        py window "$junit" || status=1
    else
        echo "nextest wrote no report (looked in ${places[*]}); nothing logged."
        status=1
    fi
    return "$status"
}

# One proof in slot folder $1: the working copy, then the patch; the check
# must fail with the patch's expect text.
prove_one() {
    local slot="$1" target="$2" jobs="$3" check="$4" test="$5" out="$6"
    local expect stem
    expect="$(py expect "$check")"
    stem="$(basename "$test" .rs)"
    git -C "$slot" checkout -q -f --detach "$(git rev-parse HEAD)"
    git -C "$slot" clean -q -fd
    # Uncommitted edits count: the proof runs on the code as it is now.
    git diff HEAD --binary >"$out.diff"
    if [ -s "$out.diff" ]; then git -C "$slot" apply --whitespace=nowarn "$out.diff"; fi
    rm -f "$out.diff"
    git ls-files --others --exclude-standard -z |
        while IFS= read -r -d '' f; do
            # Plain files only: another worker's checkout inside the folder
            # (a nested repository) is listed as a folder.
            [ -f "$f" ] || continue
            mkdir -p "$slot/$(dirname "$f")" && cp -p "$f" "$slot/$f"
        done
    if ! git -C "$slot" apply --whitespace=nowarn "$root/checks/proofs/$check.patch" 2>"$out.log"; then
        echo "$check=failed" >"$out"
        echo "  $check: the patch no longer applies" >>"$out.log"
        return
    fi
    if box_cargo "$slot" "$target" build --workspace --bins -j "$jobs" -q >>"$out.log" 2>&1 &&
        ! box_cargo "$slot" "$target" nextest run -p mimir-ui --test "$stem" \
            --retries 0 --no-fail-fast -j "$jobs" -E "test(=$check)" >>"$out.log" 2>&1 &&
        grep -qF -- "$expect" "$out.log"; then
        echo "$check=passed" >"$out"
    else
        echo "$check=failed" >"$out"
    fi
    # A failing check keeps its test home for a look; a proof doesn't need it.
    grep -o 'home is kept at [^ ]*' "$out.log" | awk '{print $NF}' |
        while read -r h; do
            case "$h" in "$cache"/t/*) rm -rf "$h" ;; esac
        done
}

prove() {
    local -a want=()
    case "${1:-}" in
        --changed) read -r -a want <<<"$(py changed)" ;;
        --changed-files) read -r -a want <<<"$(py changed --files)" ;;
        *) want=("$@") ;;
    esac
    local -A test_of=()
    while read -r c t; do test_of[$c]="$t"; done < <(py checks)
    if [ "${1:-}" = "" ]; then want=("${!test_of[@]}"); fi
    if [ "${#want[@]}" = 0 ]; then
        echo "No proofs to run: no check or patch changed since its last proof."
        return 0
    fi
    for c in "${want[@]}"; do
        [ -n "${test_of[$c]:-}" ] || { echo "No window check named $c."; return 1; }
        [ -f "checks/proofs/$c.patch" ] || { echo "No proof patch for $c (checks/proofs/$c.patch)."; return 1; }
    done
    # At most 6 at once, each with its own build folder, so one slot's
    # programs never replace another's while its check runs. The owner's rule:
    # 28 threads at most for everything together, so 4 always stay free.
    local max="${PROVE_JOBS:-6}" n="${#want[@]}" budget="${THREAD_BUDGET:-28}"
    local slots=$((n < max ? n : max))
    local jobs=$((budget / slots))
    [ "$jobs" -ge 1 ] || jobs=1
    # Each copy of the code proves in slots of its own (named after where the
    # copy is), so another copy's run can never move or remove them mid-proof.
    # The build folders stay shared, which keeps the libraries built once.
    local mine
    mine="$cache/prove/$(git rev-parse --show-toplevel | sha256sum | cut -c1-8)"
    local run="$mine/run-$$"
    mkdir -p "$run"
    # One proof run at a time on this PC: the slots and their build folders are
    # shared by every copy of the code, and two runs at once (two workers, or a
    # release beside a check) took each other's slots and proved nothing.
    exec 8>"$cache/prove/lock"
    if ! flock -n 8; then
        echo "Another proof run is going on this PC; waiting for it to finish."
        flock 8
    fi
    # Start the build box once: several slots starting it together can hang.
    tools/cargo.sh --version >/dev/null
    echo "Proving ${n} checks, ${slots} at a time (open files before: $(cut -f1 /proc/sys/fs/file-nr))"
    local started=$SECONDS s
    for ((s = 0; s < slots; s++)); do
        (
            local slot="$mine/slot-$s" i
            git worktree add -q --detach "$slot" HEAD 2>/dev/null ||
                { git worktree remove --force "$slot" 2>/dev/null; git worktree prune; git worktree add -q --detach "$slot" HEAD; }
            for ((i = s; i < n; i += slots)); do
                local c="${want[$i]}"
                prove_one "$slot" "$cache/target-prove/slot-$s" "$jobs" "$c" "${test_of[$c]}" "$run/$c"
            done
            git worktree remove --force "$slot"
        ) &
    done
    wait
    git worktree prune
    local -a outcomes=()
    for c in "${want[@]}"; do
        local o
        o="$(cat "$run/$c" 2>/dev/null || echo "$c=failed")"
        outcomes+=("$o")
        if [ "${o#*=}" = passed ]; then
            echo "  $c: proven (fails with \"$(py expect "$c")\" when its feature breaks)"
        else
            echo "  $c: NOT PROVEN. Its output:"
            tail -20 "$run/$c.log" | sed 's/^/      /'
        fi
    done
    rm -rf "$run"
    echo "Proofs took $((SECONDS - started)) s (open files after: $(cut -f1 /proc/sys/fs/file-nr))"
    py prove-log "${outcomes[@]}"
}

case "${1:-}" in
    window) window ;;
    real) tools/real-check.sh ;;
    prove) shift; prove "$@" ;;
    false)
        shift
        [ -n "${1:-}" ] || { echo 'Say why: tools/core-checks.sh false "why"'; exit 2; }
        py false "$@"
        ;;
    *) sed -n '2,9p' "$0"; exit 2 ;;
esac
