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
echo "install exit 0" > "$T/s25-result"
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

echo "== --unit starts a user unit"
stub systemd-run <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$T/unit"
STUB
run --unit dry other
ok "unit named after the pieces" has "$T/unit" "--user --collect --unit=mimir-finish-dry-other -- $PIECE dry other"

[ "$fails" = 0 ] || { echo "$fails check(s) failed"; cat "$T/run.out"; exit 1; }
echo "all checks passed"
