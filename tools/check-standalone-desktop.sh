#!/usr/bin/env bash
# Runs the phone's model runner (android/standalone/runner.cpp) on this PC with Gemma 4 E2B and
# its vision part: a text-only turn, then a photo and a follow-up (the photo stays in the
# context). Needs the model files in ~/.cache/mimir-llama/models (see below); not part of
# check-all. Builds in the `dev` box.
#   tools/check-standalone-desktop.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ver=$(cat tools/llama-cpp.version)
cache=$HOME/.cache/mimir-llama
src=$cache/llama.cpp-$ver
model=$cache/models/gemma-4-E2B-it-heretic-Q4_K_M.gguf
mmproj=$cache/models/gemma-4-E2B-it-heretic-mmproj-F16.gguf
for f in "$src" "$model" "$mmproj"; do [ -e "$f" ] || { echo "missing $f"; exit 1; }; done
out=$cache/build-desktop-$ver-$(pwd -P | sha256sum | cut -c1-12)
work=$(mktemp -d "$cache/check.XXXXXX")
trap 'find "$work" -mindepth 1 -delete; rmdir "$work"' EXIT
sys='<|turn>system\nYou are a helpful assistant. Use short paragraphs.<turn|>\n'
printf "$sys"'<|turn>user\nWhat is the capital of France? One word.<turn|>\n<|turn>model\n' > "$work/text.txt"
printf "$sys"'<|turn>user\n<__media__>\nWhat does the big headline say? Answer in one line.<turn|>\n<|turn>model\n' > "$work/p1.txt"
printf "$sys"'<|turn>user\n<__media__>\nWhat does the big headline say? Answer in one line.<turn|>\n<|turn>model\nMEN WALK ON MOON<turn|>\n<|turn>user\nWhich newspaper is it?<turn|>\n<|turn>model\n' > "$work/p2.txt"
run="cd '$PWD' && cmake -S android/standalone -B '$out' -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR='$src' >/dev/null && \
  cmake --build '$out' -j 8 --target desktop_check >/dev/null && \
  python3 -I -c 'import sys; from PIL import Image; im=Image.open(sys.argv[1]).convert(\"RGB\"); im.thumbnail((768,768)); open(sys.argv[2],\"wb\").write(im.tobytes()); print(\"%dx%d\" % im.size)' '$src/tools/mtmd/test-1.jpeg' '$work/pic.rgb' > '$work/size' && \
  '$out/desktop_check' '$model' - - - '$work/text.txt' 2>/dev/null && \
  '$out/desktop_check' '$model' '$mmproj' '$work/pic.rgb' \$(cat '$work/size') '$work/p1.txt' '$work/p2.txt' 2>/dev/null"
if [ "${CONTAINER_ID:-}" = "dev" ]; then bash -lc "$run"; else distrobox enter dev -- bash -lc "$run"; fi | tee "$work/out"
grep -qi "paris" "$work/out" || { echo "FAIL: the text-only turn"; exit 1; }
grep -qi "moon" "$work/out" || { echo "FAIL: the photo wasn't read"; exit 1; }
grep -qi "york times" "$work/out" || { echo "FAIL: the follow-up about the photo"; exit 1; }
echo "standalone desktop check passed"
