#!/usr/bin/env bash
# Mímir's Flatpak source: an OSTree repository served as plain files, so the software
# centre (and `flatpak update`) updates Mímir like any other app. Used by release.sh.
#   tools/release-repo.sh keygen
#   tools/release-repo.sh export <build folder> <repo> [branch]   add a build, sign, prune to 3
#   tools/release-repo.sh bundle <repo> <out.flatpak> [branch]    an offline file from the repo
#   tools/release-repo.sh files  <repo> <out folder> <repo url> [branch]
#                                                    writes mimir.flatpakrepo + the .flatpakref
#   tools/release-repo.sh verify <repo> [branch]      signature, summary and kept versions
#   tools/release-repo.sh publish <repo> <git url>    puts the repo on the gh-pages branch
# The signing key lives in its own folder, MIMIR_GPG_HOME (default
# ~/.config/mimir-release/gpg); its private half never leaves it and is never printed.
set -euo pipefail
id=io.github.odnwks.Mimir
home="${MIMIR_GPG_HOME:-$HOME/.config/mimir-release/gpg}"
keep="${MIMIR_REPO_KEEP:-3}"
say() { printf '%s\n' "$*"; }
fail() { say "$*" >&2; exit 1; }
gpgq() { gpg --homedir "$home" --batch --no-tty "$@"; }
stop_agent() { gpgconf --homedir "$home" --kill gpg-agent >/dev/null 2>&1 || true; }

fingerprint() {
    gpgq --list-keys --with-colons 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }'
}

cmd="${1:-}"; shift || true
case "$cmd" in
keygen)
    mkdir -p "$home"; chmod 700 "$home"
    if [ -n "$(fingerprint)" ]; then say "A repository signing key already exists."; exit 0; fi
    gpgq --passphrase '' --quick-generate-key "Mímir releases" ed25519 sign never >/dev/null 2>&1 ||
        fail "The signing key could not be made."
    stop_agent
    cat > "$home/BACKUP-NEEDED" <<EOF
This folder holds the key that signs Mímir's Flatpak source (the PC app's automatic updates).
If it is lost, nobody with the old copy can receive updates: everyone must install again.
Back up the whole folder (private key included) to the vault. Never print or share it.
Public part: $(fingerprint)
EOF
    chmod 600 "$home/BACKUP-NEEDED"
    say "Signing key made: $(fingerprint)"
    ;;

export)
    build="${1:?build folder}"; repo="${2:?repo}"; branch="${3:-stable}"
    [ -d "$build/files" ] && [ -f "$build/metadata" ] || fail "$build is not a Flatpak build folder."
    fpr="$(fingerprint)"; [ -n "$fpr" ] || fail "No signing key yet. Run: tools/release-repo.sh keygen"
    mkdir -p "$repo"
    [ -f "$repo/config" ] || ostree init --repo="$repo" --mode=archive-z2
    flatpak build-export --gpg-sign="$fpr" --gpg-homedir="$home" "$repo" "$build" "$branch" >&2
    # Older commits stay for static deltas; only the last $keep versions are kept.
    flatpak build-update-repo --generate-static-deltas --prune --prune-depth="$((keep - 1))" \
        --title="Mímir" --default-branch="$branch" \
        --gpg-sign="$fpr" --gpg-homedir="$home" "$repo" >&2
    touch "$repo/.nojekyll"
    stop_agent
    say "Repository updated: $repo"
    ;;

bundle)
    repo="${1:?repo}"; out="${2:?output file}"; branch="${3:-stable}"
    fpr="$(fingerprint)"
    flatpak build-bundle "$repo" "$out" "$id" "$branch" \
        --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
        ${fpr:+--gpg-sign="$fpr" --gpg-homedir="$home"} >&2
    stop_agent
    say "$out"
    ;;

files)
    repo="${1:?repo}"; out="${2:?output folder}"; url="${3:?repo url}"; branch="${4:-stable}"
    fpr="$(fingerprint)"; [ -n "$fpr" ] || fail "No signing key yet."
    mkdir -p "$out"
    key="$(gpgq --export "$fpr" | base64 -w0)"
    stop_agent
    cat > "$out/mimir.flatpakrepo" <<EOF
[Flatpak Repo]
Title=Mímir
Comment=Mímir for your PC, private by design
Homepage=${url%/flatpak/}
Url=$url
DefaultBranch=$branch
GPGKey=$key
EOF
    cat > "$out/$id.flatpakref" <<EOF
[Flatpak Ref]
Title=Mímir
Name=$id
Branch=$branch
Url=$url
IsRuntime=false
SuggestRemoteName=mimir
RuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo
GPGKey=$key
EOF
    say "Wrote $out/mimir.flatpakrepo and $out/$id.flatpakref"
    ;;

verify)
    repo="${1:?repo}"; branch="${2:-stable}"
    repo="$(cd "$repo" && pwd)"
    [ -f "$repo/summary" ] && [ -f "$repo/summary.sig" ] || fail "The repository has no signed summary."
    # A fresh user installation reads the repository the way a person's PC does: it
    # takes the public key from the .flatpakrepo and refuses a summary or commit whose
    # signature does not match.
    t="$(mktemp -d "$HOME/.cache/mimir/verify.XXXXXX")"
    trap 'rm -rf "$t"' EXIT
    "$0" files "$repo" "$t/f" "file://$repo/" "$branch" >/dev/null
    export FLATPAK_USER_DIR="$t/u"
    flatpak remote-add --user vtest "$t/f/mimir.flatpakrepo" >/dev/null 2>&1 ||
        fail "The repository file is not accepted."
    flatpak remote-ls --user --app --columns=application,branch vtest 2>/dev/null |
        grep -q "^$id[[:space:]]*$branch" || fail "The signed summary does not list $id."
    ref="app/$id/x86_64/$branch"
    n="$(ostree --repo="$repo" log "$ref" 2>/dev/null | grep -c '^commit ')"
    say "Summary signed and listing $id//$branch; $n version(s) kept."
    ;;

publish)
    repo="${1:?repo}"; remote="${2:?git url}"
    work="$(mktemp -d "$HOME/.cache/mimir/pages.XXXXXX")"
    trap 'rm -rf "$work"' EXIT
    # One fresh commit each time (old copies on the branch are not kept). The site serves the
    # repository at /flatpak/ (the address in mimir.flatpakrepo); hard links, so nothing big
    # is copied.
    mkdir -p "$work/site"
    cp -al "$repo" "$work/site/flatpak" 2>/dev/null || { rm -rf "$work/site/flatpak"; cp -a "$repo" "$work/site/flatpak"; }
    touch "$work/site/.nojekyll"
    export GIT_DIR="$work/g" GIT_WORK_TREE="$work/site"
    git init -q -b gh-pages
    git add -A -f
    TZ=UTC git -c user.name=ODN-WKS -c user.email=noreply@odnwks.invalid commit -q -m "Flatpak source"
    git -c credential.helper='!gh auth git-credential' push -q --force "$remote" gh-pages
    say "Flatpak source is on the gh-pages branch."
    ;;

*) sed -n '2,12p' "$0"; exit 2 ;;
esac
