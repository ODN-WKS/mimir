#!/usr/bin/env bash
# Moves this PC from the old self-installed Mímir to the Flatpak. Run it by hand, once.
#   tools/flatpak-switch.sh [--dry-run] [--force] [--local | --source <url or .flatpakrepo file>] [Mimir-<version>.flatpak]
# Mímir is installed from Mímir's Flatpak source, so the software centre updates it like any
# other app (Flatpak adds the source "mimir"). Which source:
#   --source X   the .flatpakrepo file or web address X;
#   --local      the repository release.sh keeps in ~/.cache/mimir/flatpak-repo (for testing);
#   neither      the public one, if it answers; otherwise the newest offline file in
#                ~/.cache/mimir/release/ (or the file given), which does not update by itself.
# What it does, in order (--dry-run only prints this):
#   1. Refuses if a task is working (unless --force).
#   2. Stops and removes Mímir's own background units (mimir.socket, mimir.service,
#      mimir-weekly.timer, mimir-weekly.service). The model runner's unit
#      (mimir-ollama.service) stays: it is the host's, and the Flatpak talks to it.
#   3. Removes the old self-installed files: ~/.local/bin/mimir, its login entry
#      ~/.config/autostart/mimir.desktop (only if it starts that old program), the old menu entry
#      mimir.desktop, the icons named mimir.png, the install record installed.json.
#      Chats, settings and phone pairings stay where they are: the Flatpak opens the
#      same folders (~/.local/share/mimir and ~/.config/mimir).
#   4. Installs the Flatpak for this user (flatpak install --user), from the source when given.
#   5. Starts its background part and checks its socket answers.
# It never touches any other app's files or units.
set -euo pipefail
dry=0; force=0; file=""; source=""; local_=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) dry=1 ;;
        --force) force=1 ;;
        --local) local_=1 ;;
        --source) shift; source="${1:?--source needs a file or address}" ;;
        -*) sed -n '2,4p' "$0"; exit 2 ;;
        *) file="$1" ;;
    esac
    shift
done
id=io.github.odnwks.Mimir
say() { printf '%s\n' "$*"; }
do_() { if [ "$dry" = 1 ]; then say "would run: $*"; else "$@"; fi; }
public="https://github.com/ODN-WKS/mimir/releases/latest/download/mimir.flatpakrepo"
if [ "$local_" = 1 ]; then
    lr="$HOME/.cache/mimir/flatpak-repo"
    [ -f "$lr/summary" ] || { say "No local source yet. Build one with tools/release.sh --dry-run." >&2; exit 1; }
    lt="$(mktemp -d "$HOME/.cache/mimir/switch.XXXXXX")"; trap 'rm -rf "$lt"' EXIT
    "$(dirname "$0")/release-repo.sh" files "$lr" "$lt" "file://$lr/" stable >/dev/null
    source="$lt/mimir.flatpakrepo"
elif [ -z "$source" ] && [ -z "$file" ] && curl -sfI --max-time 15 "$public" >/dev/null 2>&1; then
    source="$public"
fi
if [ -n "$source" ]; then
    say "Flatpak source: $source"
else
    [ -n "$file" ] || file="$(ls -t "$HOME"/.cache/mimir/release/*/Mimir-*.flatpak 2>/dev/null | head -1 || true)"
    [ -f "$file" ] || { say "No source and no Mimir-<version>.flatpak found. Build one with tools/release.sh --dry-run." >&2; exit 1; }
    say "Flatpak file (it will not update by itself): $file"
fi

# 1. Don't cut off work.
old="$HOME/.local/bin/mimir"
if [ -x "$old" ] && [ "$force" = 0 ]; then
    working="$("$old" status --json 2>/dev/null | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["counts"].get("working", 0))' 2>/dev/null || echo 0)"
    if [ "${working:-0}" != 0 ]; then
        say "A task is working ($working). Let it finish, or run again with --force." >&2
        exit 1
    fi
fi

# 2. Mímir's own units (never the model runner's, never another app's).
units=(mimir.socket mimir.service mimir-weekly.timer mimir-weekly.service)
for u in "${units[@]}"; do
    do_ systemctl --user disable --now "$u" 2>/dev/null || true
done
for u in "${units[@]}"; do
    f="$HOME/.config/systemd/user/$u"
    [ ! -e "$f" ] || do_ rm -f "$f"
done
do_ systemctl --user daemon-reload
# The old app's own start-at-login entry (only that exact file, only if it starts the old program).
auto="$HOME/.config/autostart/mimir.desktop"
if [ -f "$auto" ] && grep -qx "Exec=$HOME/.local/bin/mimir\( .*\)\?" "$auto" 2>/dev/null; then
    do_ rm -f "$auto"
fi

# 3. The old self-installed files.
for f in "$HOME/.local/bin/mimir" \
         "$HOME/.local/share/applications/mimir.desktop" \
         "$HOME/.local/share/mimir/installed.json" \
         "$HOME/.local/share/icons/hicolor/scalable/apps/mimir.svg"; do
    [ ! -e "$f" ] || do_ rm -f "$f"
done
for n in 16 24 32 48 64 128 256 512; do
    f="$HOME/.local/share/icons/hicolor/${n}x${n}/apps/mimir.png"
    [ ! -e "$f" ] || do_ rm -f "$f"
done
if command -v kbuildsycoca6 >/dev/null 2>&1 && [ "$dry" = 0 ]; then kbuildsycoca6 >/dev/null 2>&1 || true; fi

# 4. Install the Flatpak (from the source when there is one).
if [ -n "$source" ]; then
    do_ flatpak remote-add --user --if-not-exists --from mimir "$source"
    do_ flatpak install --user -y --noninteractive --reinstall mimir "$id//stable"
else
    do_ flatpak install --user -y --noninteractive "$file"
fi

# 5. Start the background part and look at it.
if [ "$dry" = 1 ]; then
    say "would start: flatpak run --command=mimir $id service (in the background), then: check its socket"
    exit 0
fi
setsid -f flatpak run --command=mimir "$id" service >/dev/null 2>&1 < /dev/null
# `status` is refused from a terminal in the Flatpak (by design), so look at the socket
# instead: it exists and accepts a connection (the same harmless ping the launcher makes).
sock="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/app/$id/mimir/control/control.sock"
up() { [ -S "$sock" ] && python3 -I -c 'import socket,sys; s=socket.socket(socket.AF_UNIX); s.settimeout(2); s.connect(sys.argv[1]); s.close()' "$sock" 2>/dev/null; }
ok=0
for _ in $(seq 20); do
    if up; then ok=1; break; fi
    sleep 1
done
if [ "$ok" = 1 ]; then
    say "Mímir (Flatpak) is running. Open it from the menu."
else
    say "The Flatpak is installed, but its background part didn't answer. Open Mímir from the menu." >&2
    exit 1
fi
# The owner's own Claude Code hook (`mimir approve`) pointed at the old program.
if grep -qs "local/bin/mimir" "$HOME/.claude/settings.json" 2>/dev/null; then
    say "Note: ~/.claude/settings.json still names ~/.local/bin/mimir (a Claude Code hook). It needs the Flatpak's command instead:"
    say "    flatpak run --command=mimir $id approve"
fi
