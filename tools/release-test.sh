#!/usr/bin/env bash
# Tests tools/release.sh without GitHub and without building: a copy of the repo
# with a local "origin", a fake `gh` that records its calls, and stand-in programs.
#   tools/release-test.sh
set -euo pipefail
src="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d "${TMPDIR:-/tmp}/release-test.XXXXXX")"
trap 'gpgconf --homedir "$T/gpg" --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "$T"' EXIT
pass=0
ok() { pass=$((pass + 1)); printf '  ok: %s\n' "$*"; }
bad() { printf '  FAILED: %s\n' "$*"; printf '  output:\n'; sed 's/^/    /' "$T/out" | tail -30; exit 1; }

# The repo copy: only what the script reads.
R="$T/repo"
mkdir -p "$R/tools" "$R/docs/release-notes" "$R/crates/ui/src"
cp "$src/tools/release.sh" "$src/tools/release-repo.sh" "$R/tools/"
mkdir -p "$R/packaging/flatpak"
cp "$src/crates/ui/src/release-key.pub" "$R/crates/ui/src/"
echo 9.1 > "$R/VERSION"
printf '# Mímir 9.1\n\nTest notes.\n' > "$R/docs/release-notes/9.1.md"
git -C "$T" init -q --bare origin.git
git -C "$R" init -q -b main
git -C "$R" -c user.name=t -c user.email=t@t add -A
git -C "$R" -c user.name=t -c user.email=t@t commit -qm start
git -C "$R" remote add origin "$T/origin.git"
git -C "$R" push -q origin main

# Fake gh: release state in $FAKE_GH/state (none, draft, published); assets in $FAKE_GH/assets.
mkdir -p "$T/bin" "$T/gh" "$T/pre"
cat > "$T/bin/gh" <<'GH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_GH/calls"
state="$(cat "$FAKE_GH/state" 2>/dev/null || echo none)"
case "$1 $2" in
"api user") echo tester ;;
"auth switch") ;;
"release view")
    case "$state" in draft) echo true ;; published) echo false ;; *) echo "release not found" >&2; exit 1 ;; esac ;;
"release delete") echo none > "$FAKE_GH/state"; rm -rf "$FAKE_GH/assets" ;;
"release create")
    mkdir -p "$FAKE_GH/assets"
    prev=""
    for a in "$@"; do
        if [ "$prev" = --notes-file ]; then cp "$a" "$FAKE_GH/notes"
        elif [ "$prev" = --target ]; then echo "$a" > "$FAKE_GH/target"
        elif [ -f "$a" ]; then cp "$a" "$FAKE_GH/assets/"; fi
        prev="$a"
    done
    case " $* " in *" --draft "*) echo draft > "$FAKE_GH/state" ;; *) echo published > "$FAKE_GH/state" ;; esac ;;
*) echo "fake gh: unexpected: $*" >&2; exit 1 ;;
esac
GH
chmod +x "$T/bin/gh"

# Stand-in programs where the script expects its build.
# A tiny finished Flatpak build folder (what flatpak-builder leaves): the real signing,
# repository, deltas, pruning and bundle code runs on it.
b="$T/pre/build"
mkdir -p "$b/files/bin" "$b/export"
printf '#!/bin/sh\necho stand-in\n' > "$b/files/bin/hello"; chmod +x "$b/files/bin/hello"
printf '[Application]\nname=io.github.odnwks.Mimir\nruntime=org.freedesktop.Platform/x86_64/26.08\nsdk=org.freedesktop.Sdk/x86_64/26.08\ncommand=hello\n' > "$b/metadata"
mkdir -p "$b/files/share/metainfo"
printf '<component><releases><release version="9.1" date="2026-01-01"/></releases></component>\n' > "$b/files/share/metainfo/io.github.odnwks.Mimir.metainfo.xml"
printf 'phone app\n' > "$T/pre/app.apk"
cat > "$T/pre/mimir-release" <<'SIGN'
#!/usr/bin/env bash
case "$1" in sign) echo "sig for $2 $3" > "$2.sig" ;; verify) [ -f "$2.sig" ] ;; esac
SIGN
chmod +x "$T/pre/mimir-release"

export PATH="$T/bin:$PATH" FAKE_GH="$T/gh" MIMIR_RELEASE_CACHE="$T/cache" MIMIR_GPG_HOME="$T/gpg"
export MIMIR_PAGES_REMOTE="$T/origin.git"
export MIMIR_RELEASE_PREBUILT="$T/pre" MIMIR_RELEASE_REPO=test/repo
reset() { rm -rf "$T/gh"; mkdir -p "$T/gh"; echo "${1:-none}" > "$T/gh/state"; : > "$T/gh/calls"; }
run() { (cd "$R" && tools/release.sh "$@") > "$T/out" 2>&1; }
head="$(git -C "$R" rev-parse HEAD)"
echo "Release script tests"

# A Flatpak whose own metadata carries another version than VERSION is refused.
reset
sed -i 's/version="9.1"/version="9.0"/' "$b/files/share/metainfo/io.github.odnwks.Mimir.metainfo.xml"
run --dry-run && bad "a Flatpak with the wrong metadata version was accepted"
grep -q "Flatpak's own version isn't 9.1" "$T/out" || bad "wrong metadata version: no reason given"
sed -i 's/version="9.0"/version="9.1"/' "$b/files/share/metainfo/io.github.odnwks.Mimir.metainfo.xml"
ok "the Flatpak's metadata version must equal VERSION"

reset
run --dry-run || bad "dry run failed"
[ -f "$T/cache/release/9.1/Mimir-9.1.flatpak" ] && [ -f "$T/cache/release/9.1/mimir-9.1.apk" ] &&
    [ -f "$T/cache/release/9.1/SHA256SUMS" ] && [ -f "$T/cache/release/9.1/manifest.json.sig" ] ||
    bad "dry run: files are missing from the staging folder"
[ ! -s "$T/gh/calls" ] || bad "dry run talked to GitHub"
grep -q '"tagName":"v9.1"' "$T/cache/release/9.1/manifest.json" || bad "the update list doesn't name v9.1"
[ ! -e "$T/cache/release/.work" ] || bad "the secret work folder was left behind"
m="$T/cache/release/9.1/manifest.json"
grep -q '"url":"https://github.com/test/repo/releases/download/v9.1/Mimir-9.1.flatpak"' "$m" &&
    grep -q '"sigUrl":"https://github.com/test/repo/releases/download/v9.1/Mimir-9.1.flatpak.sig"' "$m" ||
    bad "the update list doesn't carry full download addresses"
grep -q '"apkUrl":"https://github.com/test/repo/releases/download/v9.1/mimir-9.1.apk"' "$m" &&
    grep -qE '"apkSha256":"[0-9a-f]{64}"' "$m" || bad "the update list doesn't carry the phone app's file and checksum"
ok "dry run: everything staged, nothing uploaded, no work folder left; the update list has full download addresses"

mkdir -p "$T/cache/release/9.0" && : > "$T/cache/release/9.0/old"
run || bad "second dry run failed"
[ ! -e "$T/cache/release/9.0" ] || bad "an older staged version was kept"
ok "older staged versions are removed"


# The Flatpak source (an OSTree repository) the PC app updates from.
repo_dir="$T/cache/flatpak-repo"
st="$T/cache/release/9.1"
R_="$R/tools/release-repo.sh"
[ -f "$repo_dir/summary" ] && [ -f "$repo_dir/summary.sig" ] || bad "no signed summary in the repository"
"$R_" verify "$repo_dir" > "$T/out" 2>&1 || bad "the repository does not verify"
ok "the Flatpak source is built, signed, and Flatpak accepts its summary"
python3 -I - "$st" <<'PY' || bad "the source files do not parse"
import configparser, sys
d = sys.argv[1]
r = configparser.ConfigParser(interpolation=None); r.optionxform = str
r.read(d + "/mimir.flatpakrepo", encoding="utf-8")
f = configparser.ConfigParser(interpolation=None); f.optionxform = str
f.read(d + "/io.github.odnwks.Mimir.flatpakref", encoding="utf-8")
a, b = r["Flatpak Repo"], f["Flatpak Ref"]
assert a["Title"] == "Mímir" and a["Url"] == "https://test.github.io/repo/flatpak/", a["Url"]
assert a["Homepage"] and len(a["GPGKey"]) > 40
assert b["Name"] == "io.github.odnwks.Mimir" and b["Branch"] == "stable"
assert b["Url"] == a["Url"] and b["GPGKey"] == a["GPGKey"] and b["IsRuntime"] == "false"
PY
ok "mimir.flatpakrepo and the .flatpakref parse and agree (address, key, app name)"
uid="$(gpg --homedir "$T/gpg" --list-keys --with-colons | awk -F: '$1 == "uid" { print $10; exit }')"
[ "$uid" = "Mímir releases" ] || bad "the signing key's name is '$uid'"
[ -f "$T/gpg/BACKUP-NEEDED" ] && ! grep -q 'PRIVATE' "$T/gpg/BACKUP-NEEDED" || bad "the key's backup note is missing"
ok "the signing key is named 'Mímir releases' (no name or email) and has a backup note"
cp -r "$repo_dir" "$T/tampered"; for f in summary summary.idx; do printf 'x' >> "$T/tampered/$f"; done
"$R_" verify "$T/tampered" >/dev/null 2>&1 && bad "a tampered summary was accepted"
rm -rf "$T/tampered"
ok "a tampered summary is refused"
for _ in 1 2 3 4; do run --dry-run || bad "a later dry run failed"; done
n="$(ostree --repo="$repo_dir" log app/io.github.odnwks.Mimir/x86_64/stable | grep -c '^commit ')"
[ "$n" = 3 ] || bad "the repository kept $n versions, not 3"
"$R_" verify "$repo_dir" > "$T/out" 2>&1 || bad "the pruned repository does not verify"
ok "after 5 builds the repository keeps only the last 3, and still verifies"

reset
run --draft || bad "draft failed"
[ "$(cat "$T/gh/state")" = draft ] || bad "no draft was made"
[ "$(cat "$T/gh/target")" = "$head" ] || bad "the draft's target isn't HEAD"
grep -q "Test notes" "$T/gh/notes" || bad "the release notes file wasn't used"
for f in Mimir-9.1.flatpak Mimir-9.1.flatpak.sig mimir-9.1.apk mimir.flatpakrepo io.github.odnwks.Mimir.flatpakref manifest.json manifest.json.sig SHA256SUMS; do
    [ -f "$T/gh/assets/$f" ] || bad "the draft is missing $f"
done
[ ! -e "$T/gh/assets/mimir-9.1-linux-x86_64" ] && [ "$(ls "$T/gh/assets" | wc -l)" = 8 ] ||
    bad "the draft has files beyond the Flatpak, the phone app, their signatures, the source's files and the lists"
grep -q "auth switch --user ODN-DEV" "$T/gh/calls" || bad "didn't switch to the release account"
[ "$(grep -c '^auth switch' "$T/gh/calls")" = 2 ] && [ "$(grep '^auth switch' "$T/gh/calls" | tail -1)" = "auth switch --user tester" ] ||
    bad "didn't switch back to the first account"
! git --git-dir="$T/origin.git" rev-parse -q --verify gh-pages >/dev/null || bad "a draft put the Flatpak source on GitHub Pages"
ok "draft: made at HEAD with all files; accounts switched and switched back; no Pages push"

reset draft
run --draft || bad "an existing draft: no new draft"
grep -q "release delete v9.1" "$T/gh/calls" || bad "an existing draft wasn't replaced"
ok "an existing draft is replaced"

reset published
run --draft && bad "a published release was replaced"
grep -q "release delete" "$T/gh/calls" && bad "a published release was deleted"
grep -q "already published" "$T/out" || bad "a published release: no reason given"
[ "$(grep '^auth switch' "$T/gh/calls" | tail -1)" = "auth switch --user tester" ] || bad "failed run didn't switch back"
ok "a published release is never replaced, and the account is switched back on failure"

reset
run --publish || bad "publish failed"
[ "$(cat "$T/gh/state")" = published ] || bad "publish left a draft"
git --git-dir="$T/origin.git" ls-tree -r --name-only gh-pages | grep -qx flatpak/summary &&
    git --git-dir="$T/origin.git" ls-tree -r --name-only gh-pages | grep -qx flatpak/summary.sig ||
    bad "publish did not put the signed Flatpak source on the gh-pages branch"
ok "publish: a live release, and the signed Flatpak source on the gh-pages branch"

echo changed >> "$R/crates/ui/src/release-key.pub"
reset
run --draft && bad "a dirty tree was released"
grep -q "unsaved changes" "$T/out" || bad "dirty tree: no reason given"
[ ! -s "$T/gh/calls" ] || bad "dirty tree: talked to GitHub"
git -C "$R" checkout -q crates/ui/src/release-key.pub
ok "unsaved changes are refused"

echo more > "$R/more.txt"
git -C "$R" -c user.name=t -c user.email=t@t add more.txt
git -C "$R" -c user.name=t -c user.email=t@t commit -qm local
reset
run --draft && bad "an unpushed commit was released"
grep -q "isn't on GitHub yet" "$T/out" || bad "unpushed: no reason given"
git -C "$R" push -q origin main
ok "a commit that isn't on GitHub is refused"

printf '# Notes\n\nBy %s.\n' "Raven""or" > "$R/docs/release-notes/9.1.md"
git -C "$R" -c user.name=t -c user.email=t@t commit -qam notes
git -C "$R" push -q origin main
reset
run --draft && bad "notes with a private word were released"
grep -q "Private words found in: notes.md" "$T/out" || bad "private words: the file isn't named"
[ ! -s "$T/gh/calls" ] || bad "private words: talked to GitHub"
ok "a private word in the notes stops the release and names the file"

printf 'Turb''ine inside\n' >> "$T/pre/build/files/bin/hello"
printf '# Notes\n\nFine.\n' > "$R/docs/release-notes/9.1.md"
git -C "$R" -c user.name=t -c user.email=t@t commit -qam notes2
git -C "$R" push -q origin main
run --dry-run && bad "a private word in the program was released"
grep -q "Private words found in the Flatpak build: files/bin/hello" "$T/out" || bad "private words in the program: the file isn't named"
ok "a private word in the program stops the release and names the file"

echo "All $pass release script tests passed."
