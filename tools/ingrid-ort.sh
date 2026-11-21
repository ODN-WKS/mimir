#!/usr/bin/env bash
# Pre-builds Ingrid's voice model for the phone: converts the .onnx model into onnxruntime's own
# format, already optimised for ARM phones, so the phone skips that work each time it loads her
# (about 0.6 s of 2.7 s on the S8). Then checks the result sounds exactly like the original
# before it ships. Run by the app build (android/app/build.gradle.kts), in the `dev` build box.
#   tools/ingrid-ort.sh <model.onnx> <out.ort>
set -euo pipefail
src=$(realpath "$1"); out=$(realpath -m "$2")
here=$(cd "$(dirname "$0")" && pwd)
# The converter's version matches the onnxruntime inside the app's sherpa-onnx (1.28).
ort=1.28.0; onnx=1.23.2
if [ "${CONTAINER_ID:-}" != "dev" ]; then
    exec distrobox enter dev -- bash "$here/ingrid-ort.sh" "$src" "$out"
fi
home="$HOME/.cache/mimir-voices"
venv="$home/ortvenv"
if ! "$venv/bin/python" -c "import onnxruntime as o, onnx; assert o.__version__ == '$ort'" 2>/dev/null; then
    python3 -m venv --clear "$venv"
    "$venv/bin/pip" install --quiet --disable-pip-version-check "onnxruntime==$ort" "onnx==$onnx"
fi
work="$home/work"
mkdir -p "$work"; find "$work" -mindepth 1 -delete
cp "$src" "$work/ingrid-model.onnx"
"$venv/bin/python" -I -m onnxruntime.tools.convert_onnx_models_to_ort "$work/ingrid-model.onnx" \
    --optimization_style Fixed --target_platform arm >/dev/null
"$venv/bin/python" -I "$here/ingrid-ort-check.py" "$work/ingrid-model.onnx" "$work/ingrid-model.ort"
mkdir -p "$(dirname "$out")"
mv "$work/ingrid-model.ort" "$out"
find "$work" -mindepth 1 -delete
