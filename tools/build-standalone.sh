#!/usr/bin/env bash
# Builds standalone mode's model runner (llama.cpp + android/standalone) for Android (arm64)
# into android/app/src/main/jniLibs, in the `dev` build box. llama.cpp is fetched once, at the
# version pinned in tools/llama-cpp.version, into ~/.cache/mimir-llama.
set -euo pipefail
cd "$(dirname "$0")/.."
ver=$(cat tools/llama-cpp.version)
cache=$HOME/.cache/mimir-llama
src=$cache/llama.cpp-$ver
if [ ! -d "$src" ]; then
    mkdir -p "$cache"
    curl -sfL -m 600 "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/$ver" | tar xz -C "$cache"
fi
jobs=${STANDALONE_JOBS:-14}
# Each checkout builds in its own folder (cmake ties a build folder to one source).
out=$cache/build-android-$ver-$(pwd -P | sha256sum | cut -c1-12)
build="cd '$PWD' && \
  ndk=\$HOME/Android/Sdk/ndk/29.0.14206865 && \
  cmake -S android/standalone -B '$out' -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=\$ndk/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_DIR='$src' >/dev/null && \
  cmake --build '$out' -j $jobs --target standalone && \
  \$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded '$out/libstandalone.so'"
# Gradle runs this from inside the dev box before every app build.
if [ "${CONTAINER_ID:-}" = "dev" ]; then bash -lc "$build"; else distrobox enter dev -- bash -lc "$build"; fi
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp "$out/libstandalone.so" android/app/src/main/jniLibs/arm64-v8a/
ls -la android/app/src/main/jniLibs/arm64-v8a/libstandalone.so
