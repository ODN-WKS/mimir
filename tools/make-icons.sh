#!/bin/sh
# Rebuilds every Mimir icon PNG from assets/icons/mimir-icon.svg (the rounded square) and mimir-symbol.svg
# (the closed eye); the bronze colour and the faint teal halo are added here. Needs ImageMagick (runs in the build box, like tools/cargo.sh).
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
# Owner's choice (2026-11-22, mock-up B): a golden-bronze eye, a little bigger, with one faint teal halo
# behind it (no glow on the strokes themselves).
magick $t/sym.png -resize 112% -gravity center -background none -extent 1024x1024 PNG32:$t/big.png
magick -size 1024x1024 gradient:'#E8C277'-'#A9742F' PNG32:$t/grad.png
magick $t/grad.png $t/big.png -compose CopyOpacity -composite PNG32:$t/eye.png
magick $t/big.png -fill '#1FC7D4' -colorize 100 -channel A -evaluate multiply 0.7 +channel -blur 0x34 PNG32:$t/halo.png
magick $t/halo.png $t/eye.png -compose over -composite PNG32:$t/glow.png
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
