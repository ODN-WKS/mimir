#!/bin/sh
# Rebuilds every Mimir icon PNG from assets/icons/mimir-icon.svg (the rounded square) and mimir-symbol.svg
# (the closed eye); the soft cyan glow is added here. Needs ImageMagick (runs in the build box, like tools/cargo.sh).
# PC: assets/icons/mimir-<size>.png (the sizes the Flatpak manifest installs).
# Phone: android/app/src/main/res/mipmap-*/ic_launcher_foreground.png (108 dp layer per density; the
# symbol is 75% of the layer so it sits inside the 66 dp safe circle).
set -e
cd "$(dirname "$0")/.."
if ! command -v magick >/dev/null 2>&1; then
  unset LISTEN_FDS LISTEN_PID LISTEN_FDNAMES LISTEN_PIDFDID
  box=$(cat "$HOME/.config/mimir/build-box" 2>/dev/null || echo mimir)
  exec distrobox enter "$box" -- "$PWD/tools/make-icons.sh"
fi
t=$(mktemp -d)
trap 'rm -rf "$t"' EXIT
magick -background none -density 96 assets/icons/mimir-symbol.svg PNG32:$t/sym.png
# glow = the symbol blurred wide and tight, stacked under the sharp symbol
magick $t/sym.png \( +clone -blur 0x16 \) \( +clone -blur 0x6 \) -background none -compose over \
  \( -clone 1 -clone 2 -clone 0 -background none -layers merge +repage \) -delete 0-2 PNG32:$t/glow.png
magick -background none -density 96 assets/icons/mimir-icon.svg PNG32:$t/bg.png
magick $t/bg.png $t/glow.png -composite PNG32:$t/pc.png
for n in 16 24 32 48 64 128 256 512; do
  magick $t/pc.png -resize ${n}x${n} PNG32:assets/icons/mimir-$n.png
done
res=android/app/src/main/res
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  n=${d#*:}
  magick $t/glow.png -resize 75% -background none -gravity center -extent 1024x1024 -resize ${n}x${n} PNG32:$res/mipmap-${d%:*}/ic_launcher_foreground.png
done
