#!/usr/bin/env bash
# Builds and signs a Mímir release on this PC, then (only when asked) puts it on GitHub.
#   tools/release.sh             same as --dry-run
#   tools/release.sh --dry-run   build and stage everything; uploads nothing
#   tools/release.sh --draft     stage, then make a draft release on GitHub
#   tools/release.sh --publish   stage, then make a live release on GitHub
# Staged files: ~/.cache/mimir/release/<version>/ (older versions are removed).
# The PC app's automatic updates come from a signed Flatpak source (an OSTree repository,
# ~/.cache/mimir/flatpak-repo, kept between releases; the last 3 versions stay). --publish
# puts it on the public repo's gh-pages branch (/flatpak/); --draft and --dry-run don't.
# Test hooks (tools/release-test.sh only): MIMIR_RELEASE_REPO, MIMIR_RELEASE_CACHE,
# MIMIR_PAGES_REMOTE, MIMIR_GPG_HOME, MIMIR_RELEASE_PREBUILT (a folder with a stand-in
# Flatpak build folder, "build", instead of a real build).
# Release files: Mimir-<version>.flatpak (PC, offline file), mimir-<version>.apk (phone),
# mimir.flatpakrepo and io.github.odnwks.Mimir.flatpakref (add the source / install from it),
# SHA256SUMS, manifest.json, and a .sig for the Flatpak and the manifest.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
export PATH="$PATH:$HOME/.local/bin"
repo="${MIMIR_RELEASE_REPO:-ODN-WKS/mimir}"  # also names the download addresses in manifest.json
cache="${MIMIR_RELEASE_CACHE:-$HOME/.cache/mimir}"
prebuilt="${MIMIR_RELEASE_PREBUILT:-}"
# Words that must never leave this PC in a release file.
words='raven''or|claude ''station|turb''ine|home''port'
say() { printf '%s\n' "$*"; }
fail() { say "$*" >&2; exit 1; }

mode="${1:---dry-run}"
case "$mode" in --dry-run | --draft | --publish) ;; *) sed -n '2,7p' "$0"; exit 2 ;; esac

v="$(tr -d '[:space:]' < VERSION)"
[ -n "$v" ] || fail "The VERSION file is empty."
name="Mimir-$v.flatpak"
apk="mimir-$v.apk"
stage="$cache/release/$v"
work="$cache/release/.work"
mkdir -p "$cache/release"
chmod 700 "$cache/release"

# Anything secret lives only in $work and is removed when this script ends.
switched=""
back=""
cleanup() {
    rm -rf "$work"
    if [ -n "$switched" ]; then gh auth switch --user "$back" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
rm -rf "$work"; mkdir -m 700 "$work"

if [ "$mode" != "--dry-run" ]; then
    [ -z "$(git status --porcelain --untracked-files=no)" ] ||
        fail "There are unsaved changes. Commit them first."
    git fetch -q origin main
    head="$(git rev-parse HEAD)"
    git merge-base --is-ancestor "$head" origin/main ||
        fail "This commit isn't on GitHub yet. Push it first."
fi

# ---- build -------------------------------------------------------------------------------
rm -rf "$stage"; mkdir -p "$stage"
if [ -n "$prebuilt" ]; then
    cp -r "$prebuilt/build" "$work/build"
    cp "$prebuilt/app.apk" "$stage/$apk"
    signer="$prebuilt/mimir-release"
else
    say "Building the PC app (a Flatpak)..."
    tools/cargo.sh build --release -p mimir-release --quiet
    tgt="${CARGO_TARGET_DIR:-/tmp/mimir-build/target}"
    signer="$tgt/release/mimir-release"
    # The Flatpak build also checks its own files for private words and hands back
    # the program it built, to check its version and update key below.
    MIMIR_PRIVATE_WORDS="$words" packaging/flatpak/build.sh "$work/build" "$work/mimir" >&2 ||
        fail "The PC app did not build."

    say "Building the phone app..."
    pass_file="$work/pass"
    ( umask 077; secret-tool lookup service mimir-android-signing account release > "$pass_file" ) ||
        fail "The phone app's signing password isn't in the key store."
    [ -s "$pass_file" ] || fail "The phone app's signing password isn't in the key store."
    [ -f "$HOME/.config/mimir/android-signing/release.jks" ] ||
        fail "The phone app's signing key is missing (~/.config/mimir/android-signing)."
    distrobox enter dev -- bash -lc "cd '$root/android' && export ANDROID_HOME=\$HOME/Android/Sdk && ./gradlew -q -g '$HOME/.cache/mimir/android-build' --max-workers=12 -PmimirPassFile='$pass_file' assembleRealRelease; s=\$?; ./gradlew -g '$HOME/.cache/mimir/android-build' --stop >/dev/null 2>&1; exit \$s" >&2 ||
        fail "The phone app did not build."
    shred -u "$pass_file" 2>/dev/null || rm -f "$pass_file"
    cp android/app/build/outputs/apk/real/release/app-real-release.apk "$stage/$apk"
    sdk_tools="$(ls -d "$HOME"/Android/Sdk/build-tools/*/ | sort -V | tail -1)"
    say "Phone app signature:"
    distrobox enter dev -- "${sdk_tools}apksigner" verify --print-certs "$stage/$apk" |
        grep -E 'Signer.*certificate (DN|SHA-256)' ||
        fail "The phone app's signature did not check out."
fi

# ---- privacy check, part one: the Flatpak's own files --------------------------------------------
if hits="$(grep -a -r -i -l -E "$words" "$work/build/files" "$work/build/metadata" 2>/dev/null)" && [ -n "$hits" ]; then
    say "Private words found in the Flatpak build: $(printf '%s\n' "$hits" | sed "s|$work/build/||" | paste -sd, -)" >&2
    fail "Stopped: private words in the files above. Nothing was uploaded."
fi

# ---- the Flatpak's own version (what `flatpak remote-info` shows) -------------------------------
mi="$work/build/files/share/metainfo/io.github.odnwks.Mimir.metainfo.xml"
grep -q "<release version=\"$v\"" "$mi" 2>/dev/null ||
    fail "The Flatpak's own version isn't $v (its metadata says '$(grep -o '<release version="[^"]*"' "$mi" 2>/dev/null | head -1)'). Nothing was uploaded."

# ---- the Flatpak source ----------------------------------------------------------------------
# Adds this build to the signed repository (its own key, made once), keeps the last 3 versions,
# and writes the offline file and the two small files people add the source with.
owner="${repo%%/*}"; proj="${repo##*/}"
source_url="https://$(printf '%s' "$owner" | tr 'A-Z' 'a-z').github.io/$proj/flatpak/"
fp_repo="${MIMIR_FLATPAK_REPO:-$cache/flatpak-repo}"
tools/release-repo.sh keygen >&2
tools/release-repo.sh export "$work/build" "$fp_repo" stable >&2 || fail "The Flatpak source could not be updated."
tools/release-repo.sh bundle "$fp_repo" "$stage/$name" stable >&2 || fail "The Flatpak file could not be made."
tools/release-repo.sh files "$fp_repo" "$stage" "$source_url" stable >&2
tools/release-repo.sh verify "$fp_repo" stable >&2 || fail "The Flatpak source does not check out."

# ---- sign ----------------------------------------------------------------------------------
# The PC app and the update list are signed with the owner's release key
# (ed25519). mimir-release reads a raw copy that lives only in $work.
if [ -z "$prebuilt" ]; then
    keyfile="${MIMIR_RELEASE_KEY_FILE:-$HOME/.config/mimir-release/mimir-release}"
    [ -f "$keyfile" ] || fail "The release signing key is missing ($keyfile)."
    python3 -I - "$keyfile" "$work/release.key" <<'PY'
import base64, struct, sys
text = open(sys.argv[1]).read().split("-----")[2]
b = base64.b64decode("".join(text.split()))
assert b.startswith(b"openssh-key-v1\0")
i = len(b"openssh-key-v1\0")
def take():
    global i
    n = struct.unpack(">I", b[i:i+4])[0]; i += 4
    r = b[i:i+n]; i += n
    return r
assert take() == b"none", "the key has a passphrase"
take(); take()
i += 4  # key count
take()  # public blob
p = take()
j = 8  # two check numbers
t = struct.unpack(">I", p[j:j+4])[0]; j += 4 + t
n = struct.unpack(">I", p[j:j+4])[0]; j += 4 + n
n = struct.unpack(">I", p[j:j+4])[0]; j += 4
assert n == 64
open(sys.argv[2], "wb").write(p[j:j+32])
PY
    chmod 600 "$work/release.key"
    export MIMIR_RELEASE_KEY="$work/release.key"
fi
"$signer" sign "$stage/$name" "$v" >&2
"$signer" verify "$stage/$name" "$v" >&2

# The update list the app reads: a list of releases, newest first.
sha_bin="$(sha256sum "$stage/$name" | cut -c1-64)"
dl="https://github.com/$repo/releases/download/v$v"
# The phone app's file (apkUrl, apkSha256) and the release notes (notes) ride in the same signed list.
sha_apk="$(sha256sum "$stage/$apk" | cut -c1-64)"
python3 -I - "$v" "$name" "$sha_bin" "$dl" "$apk" "$sha_apk" "docs/release-notes/$v.md" > "$stage/manifest.json" <<'PY'
import json, os, sys
v, name, sha, dl, apk, sha_apk, notes = sys.argv[1:8]
entry = {"tagName": "v" + v, "file": name, "sha256": sha, "url": dl + "/" + name, "sigUrl": dl + "/" + name + ".sig",
         "apkFile": apk, "apkSha256": sha_apk, "apkUrl": dl + "/" + apk}
if os.path.exists(notes):
    entry["notes"] = open(notes, encoding="utf-8").read().strip()
print(json.dumps([entry], ensure_ascii=False, separators=(",", ":")))
PY
"$signer" sign "$stage/manifest.json" "$v" >&2
"$signer" verify "$stage/manifest.json" "$v" >&2
say "Signatures check out against the key built into the app."

# The built-in key must be the one that signed (verify above), and the program inside
# the Flatpak must say its version.
if [ -z "$prebuilt" ]; then
    # It was built for the Flatpak's runtime, so it is run there, not on this PC.
    in_runtime() { flatpak run --user --filesystem="$work:ro" --command="$work/mimir" org.freedesktop.Platform//26.08 "$@"; }
    [ "$(in_runtime --version)" = "Mímir $v" ] || fail "The program does not say version $v."
    [ "$(in_runtime --public-key)" = "$(tr -d '[:space:]' < crates/ui/src/release-key.pub)" ] ||
        fail "The program checks updates with a different key than release-key.pub."
fi
(cd "$stage" && sha256sum "$name" "$name.sig" "$apk" mimir.flatpakrepo io.github.odnwks.Mimir.flatpakref manifest.json manifest.json.sig > SHA256SUMS)

# ---- notes ---------------------------------------------------------------------------------
notes="$stage/notes.md"
if [ -f "docs/release-notes/$v.md" ]; then
    cp "docs/release-notes/$v.md" "$notes"
else
    printf 'Mímir %s\n\nThe app for your PC (a Flatpak) and the app for your phone.\n' "$v" > "$notes"
fi

# ---- privacy check -------------------------------------------------------------------------
# Nothing that names the owner or their other projects may leave this PC.
bad=0
scan() { # file label
    if grep -a -i -q -E "$words" "$1"; then
        say "Private words found in: $2 ($(grep -a -i -o -E "$words" "$1" | sort -u | paste -sd, -))" >&2
        bad=1
    fi
}
for f in "$stage/$name" "$stage/manifest.json" "$stage/mimir.flatpakrepo" "$stage/io.github.odnwks.Mimir.flatpakref" "$notes"; do scan "$f" "$(basename "$f")"; done
if [ -z "$prebuilt" ]; then
    mkdir -p "$work/apk"
    unzip -q -o "$stage/$apk" -d "$work/apk"
    while IFS= read -r f; do scan "$f" "$apk: ${f#"$work/apk/"}"; done < <(find "$work/apk" -type f)
else
    scan "$stage/$apk" "$apk"
fi
[ "$bad" = 0 ] || fail "Stopped: private words in the files above. Nothing was uploaded."
say "Privacy check passed."

# ---- old versions --------------------------------------------------------------------------
for d in "$cache"/release/*/; do
    [ "${d%/}" = "$stage" ] || rm -rf "$d"
done

say "Staged in $stage:"
(cd "$stage" && ls -l)

[ "$mode" != "--dry-run" ] || { say "Dry run done. Nothing was uploaded."; exit 0; }

# ---- GitHub ----------------------------------------------------------------------------------
flag=--draft
[ "$mode" = "--publish" ] && flag=
back="$(gh api user -q .login 2>/dev/null)" || fail "You are not signed in to GitHub."
gh auth switch --user ODN-DEV >/dev/null 2>&1 || fail "Could not switch to the release account on GitHub."
switched=1
state="$(gh release view "v$v" --repo "$repo" --json isDraft -q .isDraft 2>/dev/null || true)"
[ "$state" != false ] || fail "v$v is already published. Bump VERSION for a new release."
if [ "$state" = true ]; then
    say "Replacing the existing draft for v$v."
    gh release delete "v$v" --repo "$repo" --yes
fi
# The Flatpak source goes live first (only on --publish), so "Update now" finds the version
# the update list announces.
if [ -z "$flag" ]; then
    tools/release-repo.sh publish "$fp_repo" "${MIMIR_PAGES_REMOTE:-https://github.com/$repo.git}" ||
        fail "The Flatpak source could not be put on GitHub Pages."
fi
# shellcheck disable=SC2086
gh release create "v$v" --repo "$repo" $flag --target "$head" --title "Mímir $v" \
    --notes-file "$notes" \
    "$stage/$name" "$stage/$name.sig" "$stage/$apk" \
    "$stage/mimir.flatpakrepo" "$stage/io.github.odnwks.Mimir.flatpakref" \
    "$stage/manifest.json" "$stage/manifest.json.sig" "$stage/SHA256SUMS"
say "v$v is on GitHub ($([ -n "$flag" ] && echo draft || echo live))."
