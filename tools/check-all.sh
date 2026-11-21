#!/usr/bin/env bash
# Every check, run on this PC before each push.
#   tools/check-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."
step() { printf '\n== %s\n' "$*"; }
started=$SECONDS

step "Version"
v="$(tr -d '[:space:]' < VERSION)"
[[ "$v" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo "VERSION must look like 0.01, got '$v'"; exit 1; }
echo "version $v"

step "Repo rules"
# The first commit was made by GitHub when the repo was created.
first=2f5b16ea968ee7ef2966889892d41afcbae57706
bad=0
while IFS='|' read -r sha an ae cn ce; do
    case "$sha" in "$first"*) continue ;; esac
    if [ "$an <$ae>" != "ODN-WKS <noreply@odnwks.invalid>" ] ||
       [ "$cn <$ce>" != "ODN-WKS <noreply@odnwks.invalid>" ]; then
        echo "commit $sha has author/committer $an <$ae> / $cn <$ce>"; bad=1
    fi
done < <(git log --format='%H|%an|%ae|%cn|%ce')
# The repository is public: commit times carry no local time zone (commit with TZ=UTC).
while IFS='|' read -r sha ad cd; do
    case "$ad$cd" in *+0000*+0000) ;; *) echo "commit $sha is not in UTC ($ad / $cd): commit with TZ=UTC"; bad=1 ;; esac
done < <(git log --format='%H|%ai|%ci')
if git log --format=%B | grep -iqE '^(co-authored-by|claude-session):'; then
    echo "a commit message has an attribution line"; bad=1
fi
[ "$bad" = 0 ] || exit 1
echo "commits ok"
# Every native call the phone app makes has its function in the phone core,
# and the other way round (a missing one crashes the app on a real phone).
kt=$(grep -ho 'external fun [A-Za-z0-9]*' android/app/src/main/java/io/github/odnwks/mimir/link/Native.kt | awk '{print $3}' | sort -u)
rs=$(grep -rho 'Java_io_github_odnwks_mimir_link_Native_[A-Za-z0-9]*' crates/phone-core/src | sed 's/.*_Native_//' | sort -u)
if [ "$kt" != "$rs" ]; then
    echo "phone app and phone core disagree on native calls:"; diff <(echo "$kt") <(echo "$rs"); exit 1
fi
echo "native calls ok ($(echo "$kt" | wc -l))"

step "Scripts"
for f in tools/*.sh; do bash -n "$f"; done
echo "scripts ok"

step "Privacy"
tools/privacy-scan.sh

step "Messages"
# Owner-facing text: no raw errors, technical words, "Claude" for Mímir,
# "hostname" or errors without a next step. Today's known ones are in
# tools/message_lint_baseline.txt; only new ones fail.
python3 -I tools/message_lint.py --check

step "Flatpak files"
# The Flatpak's manifest, desktop entry and notices (the build itself runs in tools/release.sh).
bash -n packaging/flatpak/build.sh
python3 -I - packaging/flatpak/io.github.odnwks.Mimir.yml <<'PY'
import re, sys
t = open(sys.argv[1]).read()
for need in ("app-id: io.github.odnwks.Mimir", "--socket=wayland", "--filesystem=home",
             "--talk-name=org.freedesktop.Flatpak", "sha256:"):
    assert need in t, f"the Flatpak manifest lacks {need}"
assert "--filesystem=host" not in t and "--socket=session-bus" not in t, "too wide a permission"
PY
grep -q '^Exec=mimir$' packaging/flatpak/io.github.odnwks.Mimir.desktop
echo "flatpak files ok"

step "Release gate"
# --draft and --publish against a fake GitHub (no network, no build).
tools/release-test.sh

step "Format"
tools/cargo.sh fmt --all --check

step "Lint"
tools/cargo.sh clippy --workspace --all-targets --quiet -- -D warnings

step "Test"
# Every test under nextest (one process per test, so the window checks run),
# with the core checks' results and coverage in the run log.
tools/core-checks.sh window
tools/cargo.sh test --workspace --doc --quiet

step "Proofs"
# Checks whose test or proof patch changed must still fail when their
# feature breaks.
tools/core-checks.sh prove --changed

step "Build"
tools/cargo.sh build --release --quiet
ls -l "${CARGO_TARGET_DIR:-/tmp/mimir-build/target}/release/mimir"

printf '\nAll checks passed in %d s.\n' $((SECONDS - started))
