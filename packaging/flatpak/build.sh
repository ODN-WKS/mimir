#!/usr/bin/env bash
# Builds Mímir's Flatpak on this PC.
#   packaging/flatpak/build.sh <build folder> [program copy]
# leaves the finished build in <build folder> (tools/release-repo.sh turns it into the
# signed Flatpak source and the offline file), and a copy of the built program in
# [program copy] (the release script checks its version and update key). With
# MIMIR_PRIVATE_WORDS set (a grep pattern) every file in the Flatpak is checked for them.
# The staged source, vendored crates and the build live in ~/.cache/mimir/flatpak;
# it never builds in /tmp.
# Uses flatpak-builder (or `flatpak run org.flatpak.Builder`) and the freedesktop
# runtime and SDK from Flathub, installed for this user.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="${1:?usage: build.sh <build folder> [program copy]}"
mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"
v="$(tr -d '[:space:]' < "$root/VERSION")"
id=io.github.odnwks.Mimir
W="${MIMIR_FLATPAK_CACHE:-$HOME/.cache/mimir/flatpak}"
mkdir -p "$W"
say() { printf '%s\n' "$*"; }

# ---- builder and runtimes (build tools, installed for this user) -----------------------------
if command -v flatpak-builder >/dev/null 2>&1; then
    builder=(flatpak-builder)
else
    flatpak info --user org.flatpak.Builder >/dev/null 2>&1 || flatpak install --user -y flathub org.flatpak.Builder
    builder=(flatpak run --user org.flatpak.Builder)
fi
for ref in org.freedesktop.Platform//26.08 org.freedesktop.Sdk//26.08 org.freedesktop.Sdk.Extension.rust-stable//26.08; do
    flatpak info --user "$ref" >/dev/null 2>&1 || flatpak install --user -y flathub "$ref"
done

# ---- vendored crates (kept until Cargo.lock changes) ----------------------------------------
lock="$(sha256sum "$root/Cargo.lock" | cut -c1-64)"
if [ "$(cat "$W/vendor.lock" 2>/dev/null || true)" != "$lock" ] || [ ! -d "$W/vendor" ]; then
    say "Gathering the program's parts (once per change)..."
    rm -rf "$W/vendor"
    (cd "$root" && tools/cargo.sh vendor "$W/vendor" >/dev/null)
    printf '%s\n' "$lock" > "$W/vendor.lock"
fi

# ---- stage: the source tree (no build folders, no git history, no old app files) ---------------
S="$W/stage"
rm -rf "$S"; mkdir -p "$S/src" "$S/src/android/app/src/main/assets"
(cd "$root" && tar --exclude=./target --exclude=./.git --exclude=./android --exclude=./docs --exclude=./checks --exclude=./.cargo -cf - .) | tar -xf - -C "$S/src"
cp -r "$root/android/app/src/main/assets/voices" "$S/src/android/app/src/main/assets/"
cp -al "$W/vendor" "$S/vendor" 2>/dev/null || cp -r "$W/vendor" "$S/vendor"
cat > "$S/cargo-config.toml" <<'TOML'
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
TOML
cp "$here/$id.yml" "$S/$id.yml"
sed -e "s/@VERSION@/$v/" -e "s/@DATE@/$(date +%F)/" "$here/$id.metainfo.xml.in" > "$S/src/packaging/flatpak/$id.metainfo.xml"

# ---- build ---------------------------------------------------------------------------------
say "Building the Flatpak (this takes a while)..."
(cd "$S" && "${builder[@]}" --user --force-clean --jobs=12 \
    --state-dir="$W/state" --disable-rofiles-fuse \
    --default-branch=stable "$W/build" "$id.yml") >&2
if [ -n "${MIMIR_PRIVATE_WORDS:-}" ]; then
    if hits="$(grep -a -r -i -l -E "$MIMIR_PRIVATE_WORDS" "$W/build/files" "$W/build/metadata" 2>/dev/null)" && [ -n "$hits" ]; then
        say "Private words found in the Flatpak's files:" >&2
        printf '%s\n' "$hits" | sed "s|$W/build/||" >&2
        exit 1
    fi
    say "No private words in the Flatpak's files." >&2
fi
[ -z "${2:-}" ] || cp "$W/build/files/bin/mimir" "$2"

# ---- hand over the build; nothing else stays ---------------------------------------------------
rm -rf "$out"
mv "$W/build" "$out"
rm -rf "$S"
say "Build: $out"
