#!/usr/bin/env bash
# Tests tools/piece.sh on a throwaway repo in RAM. claude, run-checks, the S25 update, systemctl,
# handoff-sync and distrobox are small stubs: no model, no device, nothing real is touched.
#   tools/test_piece.sh
set -euo pipefail
PIECE=$(cd "$(dirname "$0")" && pwd -P)/piece.sh
T=/tmp/mimir-build/piece-test
rm -rf "$T"
mkdir -p "$T/bin" "$T/cache" "$T/build" "$T/briefs"
trap 'rm -rf "$T"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t.invalid GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t.invalid

stub() { cat > "$T/bin/$1"; chmod +x "$T/bin/$1"; }
stub claude <<'EOF'
#!/usr/bin/env bash
# claude -p --agent <name> ... <prompt>
agent=$3; prompt=${!#}; p=$(basename "$PWD"); p=${p#wt-}
echo "$agent $p" >> "$T/calls"
if [ -e "$T/limit" ]; then echo "You've hit your weekly limit · resets 10pm"; exit 1; fi
# A scripted answer: say-<agent>-<piece> is printed, rc- is the exit code, verdict- the results file.
if [ -e "$T/say-$agent-$p" ]; then
    if [ -e "$T/verdict-$p" ]; then cp "$T/verdict-$p" "$(grep -oE "[^ ]+/verify-$p\.md" <<< "$prompt" | head -1)"; fi
    cat "$T/say-$agent-$p"
    exit "$(cat "$T/rc-$agent-$p" 2> /dev/null || echo 0)"
fi
if [ "$agent" = verifier ]; then
    v=$(grep -oE "[^ ]+/verify-$p\.md" <<< "$prompt" | head -1)
    echo "VERDICT: pass" > "$v"
else
    printf 'Looked at the diff.\nBUGS: 0\nRisks: none, even when the usage limit reached text appears.\n'
fi
EOF
stub run-checks <<'EOF'
#!/usr/bin/env bash
git rev-parse HEAD >> "$T/checked"
[ ! -e "$T/checks-fail" ]
EOF
stub s25-update <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$T/s25"
if [ -e "$T/s25-fails" ]; then printf 'apk exit 0\ninstall exit 1 at 10:00\n' > "$T/s25-result"
elif [ -e "$T/s25-silent" ]; then exit 1
else echo "install exit 0 at 10:00" > "$T/s25-result"; fi
EOF
stub systemctl <<'EOF'
#!/usr/bin/env bash
# One running builder, reported once, so the wait is exercised.
if [ -e "$T/busy" ]; then rm "$T/busy"; echo "mimir-clean-b.service loaded active running x"; fi
EOF
stub handoff-sync <<'EOF'
#!/usr/bin/env bash
echo sync >> "$T/synced"
EOF
stub distrobox <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$T/distrobox"
EOF
export T PATH="$T/bin:$PATH"
export PIECE_CACHE=$T/cache PIECE_REPO=$T/repo PIECE_BRIEFS=$T/briefs PIECE_HANDOFF=$T/HANDOFF.md
export PIECE_BUILD=$T/build PIECE_RUN_CHECKS=$T/bin/run-checks PIECE_S25_UPDATE=$T/bin/s25-update
export PIECE_S25_RESULT=$T/s25-result PIECE_S25_TREE=$T/s25-tree PIECE_POLL=1

# Pieces "clean" and "clash" both change a.txt from the same start, so the second conflicts.
setup() {
    rm -rf "$T/repo" "$T/cache" "$T/calls" "$T/checked" "$T/s25" "$T/synced" "$T/limit" "$T/checks-fail" "$T/distrobox"
    rm -rf "$T"/say-* "$T"/rc-* "$T"/verdict-* "$T/s25-fails" "$T/s25-silent" "$T/build"
    mkdir -p "$T/build"
    mkdir -p "$T/cache"
    printf '# Hand-off\n\n## 1. Where things stand\n- older\n' > "$T/HANDOFF.md"
    printf 'Checks: `tools/cargo.sh test -p demo` and testDevDebugUnitTest.\n' > "$T/briefs/B.md"
    git init -q -b main "$T/repo"
    mkdir -p "$T/repo/tools" "$T/repo/android"
    printf '#!/bin/sh\necho "cargo $*" >> "$T/tests"\n' > "$T/repo/tools/cargo.sh"
    printf '#!/bin/sh\necho "headcheck $*" >> "$T/tests"\n' > "$T/repo/tools/headcheck.sh"
    chmod +x "$T/repo/tools/"*.sh
    echo start > "$T/repo/a.txt"
    : > "$T/repo/android/keep"
    git -C "$T/repo" add -A
    git -C "$T/repo" commit -qm start
    BASE=$(git -C "$T/repo" rev-parse HEAD)
}
piece() { # <name> <file> <text>; an empty file name means no commit
    git -C "$T/repo" worktree add -q -b "$1" "$T/cache/wt-$1" "$BASE"
    if [ -n "$2" ]; then
        echo "$3" > "$T/cache/wt-$1/$2"
        git -C "$T/cache/wt-$1" add -A
        git -C "$T/cache/wt-$1" commit -qm "$1"
    fi
    echo report > "$T/cache/wt-$1/REPORT.md"
    printf 'BRIEFS="B.md"\nBASE=%s\n' "$BASE" > "$T/cache/piece-$1.conf"
}
fails=0
ok() { # <what> <command...>
    local what=$1
    shift
    if "$@" > /dev/null 2>&1; then echo "ok   $what"; else echo "FAIL $what"; fails=$((fails + 1)); fi
}
has() { grep -qF -- "$2" "$1"; }
lacks() { ! grep -qF -- "$2" "$1"; }
main_has() { [ "$(git -C "$T/repo" show main:"$1")" = "$2" ]; }
no_branch() { ! git -C "$T/repo" rev-parse -q --verify "refs/heads/$1"; }
run() { rc=0; bash "$PIECE" "$@" > "$T/run.out" 2>&1 || rc=$?; }

echo "== clean piece joins, conflicting piece is left out"
setup
piece clean a.txt clean
piece clash a.txt clash
mkdir -p "$T/build/piece-clean"
touch "$T/busy"
run clean clash
L=$T/cache/piece-clean-clash.log
ok "exit says something was left out" [ "$rc" = 1 ]
ok "waited for the builder unit" has "$L" "clean: waiting for mimir-clean-b.service"
ok "main moved to the clean piece" main_has a.txt clean
ok "conflict has its reason" has "$L" "clash left out: it conflicts with what is already joined"
ok "checks ran on what became main" [ "$(cat "$T/checked")" = "$(git -C "$T/repo" rev-parse main)" ]
ok "S25 updated to main" [ "$(cat "$T/s25")" = "$(git -C "$T/repo" rev-parse main)" ]
ok "hand-off line added under section 1" bash -c "grep -A1 '^## 1' '$T/HANDOFF.md' | grep -q 'joined: clean'"
ok "hand-off synced" [ -s "$T/synced" ]
ok "joined branch removed" no_branch clean
ok "joined worktree removed" [ ! -e "$T/cache/wt-clean" ]
ok "joined build folder removed" [ ! -e "$T/build/piece-clean" ]
ok "report kept" [ -s "$T/cache/report-clean.md" ]
ok "left-out worktree kept" [ -d "$T/cache/wt-clash" ]
ok "left-out branch untouched" [ "$(git -C "$T/repo" show clash:a.txt)" = clash ]

echo "== usage limit: fallback verdict and NOT REVIEWED"
setup
piece lim b.txt lim
touch "$T/limit"
rm -f "$T/tests"
run lim
ok "exit 0" [ "$rc" = 0 ]
ok "fallback verdict" has "$T/cache/verify-lim.md" "VERDICT: pass (fallback, no bite check)"
ok "NOT REVIEWED" has "$T/cache/review-lim.md" "NOT REVIEWED"
ok "brief's cargo test ran" has "$T/tests" "cargo test -j12 -p demo"
ok "headcheck ran" has "$T/tests" "headcheck lim"
ok "Android tests ran" has "$T/distrobox" "testDevDebugUnitTest"
ok "hand-off says review it" has "$T/HANDOFF.md" "NOT REVIEWED, review before any release: lim"
ok "main moved" main_has b.txt lim

echo "== empty commit list, unfinished and dirty pieces"
setup
piece empty "" ""
piece noreport c.txt c
rm "$T/cache/wt-noreport/REPORT.md"
piece dirty d.txt d
echo more > "$T/cache/wt-dirty/extra"
before=$(git -C "$T/repo" rev-parse main)
run empty noreport dirty
L=$T/cache/piece-empty-noreport-dirty.log
ok "exit 1" [ "$rc" = 1 ]
ok "plain reason for no commits" has "$L" "empty left out: no commits on the branch beyond main"
ok "no git usage text" lacks "$T/run.out" "usage: git"
ok "missing report said" has "$L" "noreport left out: no REPORT.md"
ok "dirty tree said" has "$L" "dirty left out: the worktree has uncommitted"
ok "no model called" [ ! -e "$T/calls" ]
ok "main unchanged" [ "$(git -C "$T/repo" rev-parse main)" = "$before" ]
ok "no S25 update" [ ! -e "$T/s25" ]

echo "== failing checks leave main and the piece alone"
setup
piece red e.txt e
touch "$T/checks-fail"
before=$(git -C "$T/repo" rev-parse main)
run red
ok "exit 1" [ "$rc" = 1 ]
ok "main unchanged" [ "$(git -C "$T/repo" rev-parse main)" = "$before" ]
ok "no S25 update" [ ! -e "$T/s25" ]
ok "piece kept" [ -d "$T/cache/wt-red" ]

echo "== dry run changes nothing"
setup
piece dry f.txt f
state() { (cd "$T" && find repo cache build HANDOFF.md -type f ! -name index -exec sha256sum {} + | sort); }
before=$(state)
run --dry-run dry
ok "exit 0" [ "$rc" = 0 ]
ok "plan printed" has "$T/run.out" "ready: 1 commit(s)"
ok "nothing changed" [ "$(state)" = "$before" ]
ok "no model, checks or S25" [ ! -e "$T/calls" ]

# say <agent> <piece> <exit code> <line>...: what that stubbed model prints.
say() { local a=$1 p=$2 rc=$3; shift 3; printf '%s\n' "$@" > "$T/say-$a-$p"; echo "$rc" > "$T/rc-$a-$p"; }
tip() { git -C "$T/repo" rev-parse "$1"; }
main_is() { [ "$(tip main)" = "$1" ]; }
stacked() { # <name> <on piece> <file> <text>
    local keep=$BASE
    BASE=$(tip "$2")
    piece "$1" "$3" "$4"
    BASE=$keep
}

echo "== a stacked piece never carries unverified commits onto main"
setup
piece low b.txt low
stacked high low c.txt high
say verifier low 0 "tests failed"
echo "VERDICT: fail" > "$T/verdict-low"
before=$(tip main)
run low high
L=$T/cache/piece-low-high.log
ok "exit 1" [ "$rc" = 1 ]
ok "main unchanged" main_is "$before"
ok "stacked piece refused for its BASE" has "$L" "high left out: BASE"
ok "no model called for it" lacks "$T/calls" "verifier high"
run high
ok "alone: refused too" has "$T/cache/piece-high.log" "high left out: BASE"
ok "alone: main unchanged" main_is "$before"

setup
piece low b.txt low
stacked high low c.txt high
run --dry-run low high
ok "dry run: stacked on an earlier piece" has "$T/run.out" "stacked on low"
run low high
ok "stacked on a joined piece: exit 0" [ "$rc" = 0 ]
ok "both on main" main_has c.txt high
ok "the lower one too" main_has b.txt low

setup
piece clean a.txt clean
piece low a.txt low
stacked high low c.txt high
run clean low high
ok "stacked on a piece that did not join: left out" has "$T/cache/piece-clean-low-high.log" "high left out: it builds on low, which did not join"
ok "its file is not on main" [ ! -e "$T/repo/c.txt" ]
ok "main holds only the clean piece" [ "$(git -C "$T/repo" ls-tree --name-only main | grep -c c.txt)" = 0 ]

setup
piece off b.txt off
git -C "$T/repo" commit -q --allow-empty -m later
printf 'BRIEFS="B.md"\nBASE=%s\n' "$(tip main)" > "$T/cache/piece-off.conf"
run off
ok "BASE outside the branch refused" has "$T/cache/piece-off.log" "is not an ancestor of the branch"

echo "== a failed verification or review is never taken for a usage limit"
unsafe() { # <what>: one piece "x" that must stay off main
    before=$(tip main)
    run x
    ok "$1: exit 1" [ "$rc" = 1 ]
    ok "$1: main unchanged" main_is "$before"
}
setup
piece x b.txt x
say verifier x 0 "I checked the usage limit reached branch of the script; tests FAILED"
unsafe "no verdict file, limit words in the answer"
ok "no fallback ran" [ ! -e "$T/cache/fallback-x.log" ]
setup
piece x b.txt x
say verifier x 1 "Report:" "the bite check fails" "the test string is" "usage limit reached" "so it failed"
unsafe "long answer, non-zero exit"
ok "no fallback ran" [ ! -e "$T/cache/fallback-x.log" ]
setup
piece x b.txt x
say verifier x 1 "Error: something broke"
echo "VERDICT: pass" > "$T/verdict-x"
unsafe "verifier wrote pass but exited non-zero"
setup
piece x b.txt x
say reviewer x 0 "**BUGS: 2**" "1. the 'session limit reached' banner erases typed text"
unsafe "bold BUGS line and limit words"
ok "marked as failed review" has "$T/cache/review-x.md" "REVIEW FAILED"
setup
piece x b.txt x
say reviewer x 0 "Usage limit reached"
unsafe "limit words with exit 0"
setup
piece x b.txt x
say reviewer x 1 "BUGS: 0" "then it crashed"
unsafe "reviewer exited non-zero"
setup
piece x b.txt x
say reviewer x 1 "Claude AI usage limit reached|1764200000"
run x
ok "a real limit notice is still NOT REVIEWED" has "$T/cache/review-x.md" "NOT REVIEWED"

echo "== every BUGS line counts"
setup
piece x b.txt x
say reviewer x 0 "BUGS: 0" "On a second look:" "BUGS: 1" "x.txt:1 loses text"
unsafe "later BUGS: 1"
setup
piece x b.txt x
say reviewer x 0 "**BUGS: 2**" "BUGS: 0"
unsafe "earlier bold BUGS: 2"

echo "== other jobs' join folders and commits are left alone"
setup
piece x b.txt x
git -C "$T/repo" worktree add -q -b integ "$T/cache/wt-integ" main
git -C "$T/cache/wt-integ" commit -q --allow-empty -m unjoined-work
held=$(tip integ)
mkdir -p "$T/build/rc-integ"
: > "$T/build/rc-integ/in-use"
run x
ok "exit 0" [ "$rc" = 0 ]
ok "integ still holds its commit" [ "$(tip integ)" = "$held" ]
ok "integ folder still there" [ "$(git -C "$T/cache/wt-integ" rev-parse HEAD)" = "$held" ]
ok "other job's build folder kept" [ -e "$T/build/rc-integ/in-use" ]
ok "main moved" main_has b.txt x

saved() { [ -n "$(git -C "$T/repo" branch --list 'piece-join-saved-*' --contains "$1")" ]; }
setup
piece x b.txt x
git -C "$T/repo" worktree add -q -b piece-join "$T/cache/wt-piece-join" main
git -C "$T/cache/wt-piece-join" commit -q --allow-empty -m hand-fix
held=$(tip piece-join)
run x
ok "commit left on the join branch is saved" saved "$held"
ok "save is logged" has "$T/cache/piece-x.log" "saved as piece-join-saved-"
setup
piece x b.txt x
git -C "$T/repo" worktree add -q --detach "$T/cache/wt-piece-join" main
git -C "$T/cache/wt-piece-join" commit -q --allow-empty -m detached-fix
held=$(git -C "$T/cache/wt-piece-join" rev-parse HEAD)
run x
ok "commit on a detached join folder is saved" saved "$held"

echo "== a failed S25 install is a failure"
for how in s25-fails s25-silent; do
    setup
    piece x b.txt x
    touch "$T/$how"
    run x
    L=$T/cache/piece-x.log
    ok "$how: exit not 0" [ "$rc" != 0 ]
    ok "$how: said in the log" has "$L" "S25 update FAILED"
    ok "$how: said in the hand-off" has "$T/HANDOFF.md" "S25: UPDATE FAILED"
    ok "$how: worktree kept" [ -d "$T/cache/wt-x" ]
    ok "$how: branch kept" [ -n "$(git -C "$T/repo" branch --list x)" ]
    ok "$how: main still moved" main_has b.txt x
done

echo "== --unit starts a user unit"
stub systemd-run <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$T/unit"
STUB
run --unit dry other
ok "unit named after the pieces" has "$T/unit" "--user --collect --unit=mimir-finish-dry-other -- $PIECE dry other"

[ "$fails" = 0 ] || { echo "$fails check(s) failed"; cat "$T/run.out"; exit 1; }
echo "all checks passed"
