#!/usr/bin/env bash
# Finishes one or more built pieces without a model between the steps: wait for the builder, verify, review,
# join what passed onto main, full checks, S25 update, hand-off line, clean up. Never pushes, never restarts.
#   tools/piece.sh <piece>...             run here
#   tools/piece.sh --unit <piece>...      run as its own systemd user unit (survives the chat ending)
#   tools/piece.sh --dry-run <piece>...   print the steps, change nothing
# A piece is branch <piece> in worktree ~/.cache/mimir/wt-<piece>, with ~/.cache/mimir/piece-<piece>.conf:
#   BRIEFS="P24-back-goes-home.md P24-F1-keep-typed-text.md"   (names in the briefs folder, or full paths)
#   BASE=<commit the piece started from>
# Every step: ~/.cache/mimir/piece-<pieces>.log. The PIECE_* variables exist for tools/test_piece.sh.
set -u
unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
# Appended, so a unit finds claude and handoff-sync but a caller's own PATH still wins.
export PATH="$PATH:$HOME/.local/bin"

M=${PIECE_CACHE:-$HOME/.cache/mimir}
R=${PIECE_REPO:-$HOME/Projects/Mimir}
BRIEF_DIR=${PIECE_BRIEFS:-$R/docs/notes/docs/designs/rebuild/redesign/briefs}
HANDOFF=${PIECE_HANDOFF:-$R/docs/notes/docs/designs/audit/HANDOFF.md}
BUILD=${PIECE_BUILD:-/tmp/mimir-build}
RUN_CHECKS=${PIECE_RUN_CHECKS:-$HOME/.claude/hooks/run-checks.sh}
S25_UPDATE=${PIECE_S25_UPDATE:-$M/s25-update.sh}
S25_RESULT=${PIECE_S25_RESULT:-$M/s25-update-result.txt}
S25_TREE=${PIECE_S25_TREE:-/tmp/mimir-integ}
POLL=${PIECE_POLL:-30}
INTEG=$M/wt-integ
LIMIT_RE="hit your [a-z0-9 -]*limit|usage limit reached|out of (extra )?usage|(session|weekly|5-hour|usage) limit (reached|will reset)"

die() { echo "piece: $*" >&2; exit 2; }
usage() { die "use: tools/piece.sh [--unit|--dry-run] <piece>..."; }
g() { TZ=UTC git -c user.name=ODN-WKS -c user.email=noreply@odnwks.invalid "$@"; }

mode=run
case "${1:-}" in
    --unit) mode=unit; shift ;;
    --dry-run) mode=dry; shift ;;
    -*) usage ;;
esac
[ $# -gt 0 ] || usage
PIECES=("$@")
for p in "${PIECES[@]}"; do
    [[ "$p" =~ ^[a-z0-9][a-z0-9-]*$ ]] || die "bad piece name '$p'"
    case "$p" in main | integ | target | android-out | android-target) die "'$p' is a reserved name" ;; esac
done
NAME=$(IFS=-; echo "${PIECES[*]}")
L=$M/piece-$NAME.log
OUT=$M/piece-$NAME.out

if [ "$mode" = unit ]; then
    self=$(readlink -f "$0")
    systemd-run --user --collect --unit="mimir-finish-$NAME" -- "$self" "${PIECES[@]}" || die "could not start the unit"
    echo "started mimir-finish-$NAME; log: $L"
    exit 0
fi

log() { local line; line="$(date +%H:%M:%S) $*"; echo "$line"; echo "$line" >> "$L"; }

# Names of this piece's builder units that are still running (mimir-p2 must not match mimir-p24).
builder_units() {
    local all
    all=$(systemctl --user list-units 'mimir-*' --state=active,activating,deactivating,reloading --no-legend --plain) || return 1
    awk '{print $1}' <<< "$all" | grep -E "^mimir-$1([^0-9].*)?\.(service|scope)$" || true
}

# Changed or untracked files, apart from the two report files a builder leaves.
dirty() { git -C "$1" --no-optional-locks status --porcelain | grep -vE '^\?\? (REPORT\.md|\.agent-results\.md)$'; }

# Reads the piece's conf and checks it can be finished. Sets BRIEF_LIST, BASE, TIP; or WHY and returns 1.
ready() {
    local p=$1 wt=$M/wt-$1 conf=$M/piece-$1.conf b n
    local BRIEFS=""
    BASE="" WHY="" TIP="" BRIEF_LIST=""
    [ -f "$conf" ] || { WHY="no conf file $conf"; return 1; }
    # shellcheck disable=SC1090
    . "$conf"
    [ -n "$BRIEFS" ] && [ -n "$BASE" ] || { WHY="$conf needs BRIEFS and BASE"; return 1; }
    for b in $BRIEFS; do
        case "$b" in /*) ;; *) b=$BRIEF_DIR/$b ;; esac
        [ -f "$b" ] || { WHY="brief not found: $b"; return 1; }
        BRIEF_LIST="$BRIEF_LIST $b"
    done
    [ -d "$wt" ] || { WHY="no worktree $wt"; return 1; }
    [ "$(git -C "$wt" symbolic-ref -q --short HEAD)" = "$p" ] || { WHY="$wt is not on branch $p"; return 1; }
    git -C "$wt" rev-parse -q --verify "$BASE^{commit}" > /dev/null || { WHY="BASE $BASE is not a commit"; return 1; }
    [ -f "$wt/REPORT.md" ] || { WHY="no REPORT.md: the builder did not finish"; return 1; }
    [ -z "$(dirty "$wt")" ] || { WHY="the worktree has uncommitted or untracked files"; return 1; }
    TIP=$(git -C "$wt" rev-parse HEAD)
    n=$(git -C "$R" rev-list --count "main..$TIP") || { WHY="cannot compare the branch with main"; return 1; }
    [ "$n" -gt 0 ] || { WHY="no commits on the branch beyond main, so there is nothing to join"; return 1; }
}

claude_run() { # <worktree> <agent> <output file> <prompt>
    (cd "$1" && claude -p --agent "$2" --model sonnet --permission-mode auto "$4" < /dev/null > "$3" 2>&1)
}

android_clean() { # removes a worktree's Gradle output, including the folder in memory it links to
    local wt=$1 real
    real=$(readlink -f "$wt/android/app/build" 2> /dev/null)
    case "$real" in "$BUILD"/android-out/*/app) rm -rf "${real%/app}" ;; esac
    rm -rf "$wt/android/app/build" "$wt/android/app/src/main/jniLibs"
}

ram_clean() { # also the phone core's build folder, named after the worktree's path
    local hash
    hash=$(cd "$1" 2> /dev/null && pwd -P | sha256sum | cut -c1-12) || return 0
    android_clean "$1"
    rm -rf "${BUILD:?}/android-target/$hash"
}

# Without Claude: the piece's cargo and Android tests, then headcheck.
fallback() { # <piece>
    local p=$1 wt=$M/wt-$1 files crates c dir
    files=$(git -C "$wt" diff --name-only "$BASE" "$TIP")
    # shellcheck disable=SC2086
    crates=$({
        grep -ohE 'cargo\.sh +(test|nextest run)[^`]* -p +[a-z0-9-]+' $BRIEF_LIST | grep -oE -- '-p +[a-z0-9-]+' | awk '{print $2}'
        for dir in $(grep -oE '^crates/[^/]+' <<< "$files" | sort -u); do
            git -C "$wt" show "$TIP:$dir/Cargo.toml" 2> /dev/null | sed -n 's/^name *= *"\([^"]*\)".*/\1/p' | head -1
        done
    } | sort -u)
    mkdir -p "$BUILD"
    cd "$wt" || return 1
    for c in $crates; do
        echo "== cargo test -p $c"
        CARGO_TARGET_DIR=$BUILD/piece-$p tools/cargo.sh test -j12 -p "$c" || return 1
    done
    # shellcheck disable=SC2086
    if grep -qE '^(android|crates/phone-core)/' <<< "$files" || grep -q testDevDebugUnitTest $BRIEF_LIST; then
        echo "== Android unit tests"
        flock -o "$BUILD/gradle-p21.lock" distrobox enter dev -- bash -lc \
            "cd '$wt/android' && export ANDROID_HOME=\$HOME/Android/Sdk && ./gradlew -g \$HOME/.cache/mimir/android-build --max-workers=12 testDevDebugUnitTest" || return 1
    fi
    echo "== headcheck"
    flock -o "$BUILD/gradle-p21.lock" tools/headcheck.sh "$p"
}

verify() { # <piece>: leaves the verdict in $M/verify-<piece>.md
    local p=$1 wt=$M/wt-$1 v=$M/verify-$1.md o=$M/out-verify-$1.txt
    rm -f "$v"
    # The verifier writes its own results file; the builder's is kept aside.
    [ -f "$wt/.agent-results.md" ] && mv -f "$wt/.agent-results.md" "$M/results-$p-builder.md"
    claude_run "$wt" verifier "$o" "Verify one finished piece of the Mímir project. You never edit code.
Brief(s):$BRIEF_LIST. Common rules: $BRIEF_DIR/COMMON.md. Worktree $wt, branch $p, commit $TIP (confirm HEAD and a clean tree first; REPORT.md may be untracked). Builder report: $wt/REPORT.md.
1. Re-run every check command in the brief(s) on that commit (Android in the dev box under flock $BUILD/gradle-p21.lock). If podman says 'bad file descriptor', unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID first.
2. Bite check: the new tests on the code of $BASE must fail (git checkout $BASE -- the non-test files the piece changed, run the new tests, then git checkout $TIP -- the same files; end clean at $TIP).
3. Remove Gradle output left in the worktree (android/app/build, android/app/src/main/jniLibs).
No devices, no push, no writes under Claude's settings folder, never the owner's running Mímir or its data.
Write your findings to $v with a first line exactly 'VERDICT: pass' or 'VERDICT: fail', then each command's exit code and last lines."
    if head -1 "$v" 2> /dev/null | grep -qE '^VERDICT: (pass|fail)'; then
        :
    elif grep -qiE "$LIMIT_RE" "$o"; then
        log "$p: Claude usage limit, running the brief's tests here instead"
        if (fallback "$p") > "$M/fallback-$p.log" 2>&1; then
            echo "VERDICT: pass (fallback, no bite check)" > "$v"
        else
            echo "VERDICT: fail (fallback, no bite check; see fallback-$p.log)" > "$v"
        fi
        android_clean "$wt"
    else
        echo "VERDICT: fail (the verifier wrote no verdict; see $o)" > "$v"
    fi
    if [ "$(git -C "$wt" rev-parse HEAD)" != "$TIP" ] || [ -n "$(dirty "$wt")" ]; then
        echo "VERDICT: fail (verification left the worktree changed)" > "$v"
    fi
}

review() { # <piece>: leaves the answer in $M/review-<piece>.md
    local p=$1 wt=$M/wt-$1 v=$M/review-$1.md o=$M/out-review-$1.txt
    claude_run "$wt" reviewer "$o" "Review one finished piece of the Mímir project for real bugs only. Read-only. Brief(s):$BRIEF_LIST. Diff: in $wt run git diff $BASE $TIP. Builder report: $wt/REPORT.md.
Look hardest at behaviour the owner would see that differs from the brief, anything that loses typed text, messages or settings, and anything that breaks other navigation.
Print your answer; one line must be exactly 'BUGS: <number of confirmed bugs>' and come first, then each bug with file:line, a concrete input and what goes wrong, then risks that are not bugs."
    sed -n '/^BUGS:/,$p' "$o" > "$v"
    [ -s "$v" ] && return 0
    if grep -qiE "$LIMIT_RE" "$o"; then
        echo "NOT REVIEWED (Claude usage limit)" > "$v"
    else
        echo "REVIEW FAILED (no BUGS: line; see $o)" > "$v"
    fi
}

plan() {
    local p units
    echo "Dry run: nothing is changed. Log would be $L"
    for p in "${PIECES[@]}"; do
        echo "== $p"
        units=$(builder_units "$p" | tr '\n' ' ')
        if [ -n "$units" ]; then echo "1. wait for: $units"; else echo "1. no builder unit is running"; fi
        if ready "$p"; then
            echo "2. ready: $(git -C "$R" rev-list --count "main..$TIP") commit(s), tip ${TIP:0:7}, briefs:$BRIEF_LIST"
        else
            echo "2. would stop here: $WHY"
        fi
        echo "3. verifier (Sonnet), verdict in $M/verify-$p.md; usage limit: brief's tests and headcheck here"
        echo "4. reviewer, answer in $M/review-$p.md; usage limit: NOT REVIEWED"
    done
    echo "5. join pieces with verdict pass and BUGS: 0 (or NOT REVIEWED) by rebase onto a fresh $INTEG from main, in order: ${PIECES[*]}"
    echo "6. run $RUN_CHECKS on the joined tip; on exit 0, fast-forward main in $R"
    echo "7. $S25_UPDATE <main>, only if main moved"
    echo "8. add a line to $HANDOFF and run handoff-sync"
    echo "9. remove joined pieces' build folders in $BUILD, worktrees and branches"
}

if [ "$mode" = dry ]; then plan; exit 0; fi

mkdir -p "$M" || die "cannot make $M"
log "start: ${PIECES[*]}"
declare -A TIPS=() LEFT=()
PASSED=()
JOINED=()
NOTREVIEWED=""

leave() { LEFT[$1]=$2; log "$1 left out: $2"; }

for p in "${PIECES[@]}"; do
    # Step 1: wait for the builder.
    waited=""
    while :; do
        units=$(builder_units "$p") || { units=error; break; }
        [ -n "$units" ] || break
        [ -n "$waited" ] || log "$p: waiting for $(tr '\n' ' ' <<< "$units")"
        waited=1
        sleep "$POLL"
    done
    if [ "$units" = error ]; then leave "$p" "cannot ask systemd whether the builder is still running"; continue; fi
    # Step 2.
    if ! ready "$p"; then leave "$p" "$WHY"; continue; fi
    # Steps 3 and 4.
    log "$p: verifying ${TIP:0:7}"
    verify "$p"
    verdict=$(head -1 "$M/verify-$p.md")
    log "$p: $verdict"
    case "$verdict" in "VERDICT: pass"*) ;; *) leave "$p" "$verdict (review skipped)"; continue ;; esac
    log "$p: reviewing"
    review "$p"
    found=$(head -1 "$M/review-$p.md")
    log "$p: $found"
    if grep -qE '^BUGS: 0([^0-9]|$)' <<< "$found"; then :
    elif [[ "$found" == "NOT REVIEWED"* ]]; then NOTREVIEWED="$NOTREVIEWED $p"
    else leave "$p" "$found (see review-$p.md)"; continue; fi
    TIPS[$p]=$TIP
    PASSED+=("$p")
done

# Steps 5 to 7 share the join worktree and main, so only one run at a time does them.
exec 9> "$M/piece-join.lock"
flock 9
MOVED=""
S25="not run"

join() {
    local p before
    if [ -d "$INTEG" ]; then
        if [ -n "$(git -C "$INTEG" status --porcelain)" ]; then
            for p in "${PASSED[@]}"; do leave "$p" "$INTEG has uncommitted work; not touching it"; done
            return 1
        fi
        log "removing the old join worktree (it was at $(git -C "$INTEG" rev-parse --short HEAD))"
        git -C "$R" worktree remove --force "$INTEG" >> "$OUT" 2>&1
    fi
    git -C "$R" worktree prune
    if ! git -C "$R" worktree add -q -B integ "$INTEG" main >> "$OUT" 2>&1; then
        for p in "${PASSED[@]}"; do leave "$p" "cannot make the join worktree (see $OUT)"; done
        return 1
    fi
    for p in "${PASSED[@]}"; do
        before=$(git -C "$INTEG" rev-parse integ)
        # A detached copy of the piece is rebased; the piece's own branch stays as it was.
        if g -C "$INTEG" rebase integ "${TIPS[$p]}" >> "$OUT" 2>&1; then
            if [ "$(git -C "$INTEG" rev-parse HEAD)" = "$before" ]; then
                leave "$p" "its changes are already there, so there is nothing to join"
            else
                git -C "$INTEG" checkout -q -B integ HEAD >> "$OUT" 2>&1
                JOINED+=("$p")
                log "joined $p"
                continue
            fi
        else
            git -C "$INTEG" rebase --abort >> "$OUT" 2>&1
            leave "$p" "it conflicts with what is already joined (see $OUT)"
        fi
        git -C "$INTEG" checkout -q -f integ >> "$OUT" 2>&1
    done
    [ ${#JOINED[@]} -gt 0 ]
}

checks_and_merge() {
    local tip rc p why=""
    tip=$(git -C "$INTEG" rev-parse HEAD)
    cp "$R/android/local.properties" "$INTEG/android/" 2> /dev/null
    log "run-checks on ${tip:0:7} (${JOINED[*]})"
    (cd "$INTEG" && CARGO_TARGET_DIR=$BUILD/rc-integ bash "$RUN_CHECKS") > "$M/runchecks-$NAME.log" 2>&1 9>&-
    rc=$?
    log "run-checks exit $rc: $(tail -1 "$M/runchecks-$NAME.log" | cut -c1-120)"
    rm -rf "${BUILD:?}/rc-integ"
    ram_clean "$INTEG"
    if [ $rc -ne 0 ]; then why="run-checks failed on the joined code (see runchecks-$NAME.log)"
    elif [ "$(git -C "$R" symbolic-ref -q --short HEAD)" != main ]; then why="$R is not on main"
    elif ! git -C "$R" merge -q --ff-only "$tip" >> "$OUT" 2>&1; then why="main could not fast-forward (see $OUT)"
    fi
    if [ -n "$why" ]; then
        for p in "${JOINED[@]}"; do leave "$p" "$why"; done
        JOINED=()
        return 1
    fi
    MOVED=1
    log "main is now $(git -C "$R" rev-parse --short main)"
}

if [ ${#PASSED[@]} -gt 0 ] && join && checks_and_merge; then
    MAIN=$(git -C "$R" rev-parse main)
    log "S25 update to ${MAIN:0:7}"
    mkdir -p "$BUILD"
    rm -f "$S25_RESULT"
    flock -o "$BUILD/gradle-p21.lock" "$S25_UPDATE" "$MAIN" >> "$OUT" 2>&1 9>&-
    S25=$(grep -E 'apk exit|install exit|tests ' "$S25_RESULT" 2> /dev/null | tr '\n' ' ')
    S25=${S25:-"no result (see $OUT)"}
    log "S25: $S25"
    ram_clean "$S25_TREE"
    git -C "$R" worktree remove --force "$S25_TREE" >> "$OUT" 2>&1
else
    log "main unchanged; S25 not updated"
fi
exec 9>&-

# Step 8: the hand-off line, then the copy off the PC.
MAIN=$(git -C "$R" rev-parse --short main)
left=""
for p in "${PIECES[@]}"; do
    [ -n "${LEFT[$p]:-}" ] && left="$left $p (${LEFT[$p]});"
done
line="- **$(date +%H:%M) NEWEST (tools/piece.sh ${PIECES[*]}):** joined: ${JOINED[*]:-none}; left out:${left:- none;} main = $MAIN (not pushed); S25: $S25."
[ -z "$NOTREVIEWED" ] || line="$line NOT REVIEWED, review before any release:$NOTREVIEWED."
line="$line Log: $L"
if [ -f "$HANDOFF" ]; then
    new=$(mktemp)
    LINE=$line awk '{print} !done && /^## 1\. Where things stand/ {print ENVIRON["LINE"]; done=1} END {if (!done) print ENVIRON["LINE"]}' "$HANDOFF" > "$new" && cat "$new" > "$HANDOFF"
    rm -f "$new"
    log "hand-off line added"
    if handoff-sync >> "$OUT" 2>&1; then log "hand-off synced"; else log "handoff-sync failed (see $OUT)"; fi
else
    log "no hand-off file at $HANDOFF"
fi

# Step 9: only what is now on main, and only if nobody added to it meanwhile.
if [ -n "$MOVED" ]; then
    for p in "${JOINED[@]}"; do
        wt=$M/wt-$p
        if [ "$(git -C "$wt" rev-parse HEAD)" != "${TIPS[$p]}" ] || [ -n "$(dirty "$wt")" ]; then
            log "$p: kept its worktree and branch, they changed after verification"
            continue
        fi
        cp -f "$wt/REPORT.md" "$M/report-$p.md"
        ram_clean "$wt"
        rm -rf "${BUILD:?}/piece-$p" "${BUILD:?}/$p" "${BUILD:?}/headcheck-$p"
        if git -C "$R" worktree remove --force "$wt" >> "$OUT" 2>&1 && git -C "$R" branch -q -D "$p" >> "$OUT" 2>&1; then
            log "$p: cleaned up (branch was at ${TIPS[$p]:0:7}; report kept as report-$p.md)"
        else
            log "$p: could not remove its worktree or branch (see $OUT)"
        fi
    done
fi
log "done: joined ${JOINED[*]:-none}, left out ${#LEFT[@]}"
[ ${#LEFT[@]} -eq 0 ]
