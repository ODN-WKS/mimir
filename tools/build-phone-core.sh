#!/usr/bin/env bash
# Builds the phone app's Rust side (crates/phone-core) for Android (arm64, and x86_64 for dev builds)
# into android/app/src/main/jniLibs, in the `dev` build box.
set -euo pipefail
cd "$(dirname "$0")/.."
# Each checkout gets its own build folder: a shared one let another checkout's
# core land in this app (the PC and phone then spoke different messages).
target="/tmp/mimir-build/android-target/$(pwd -P | sha256sum | cut -c1-12)"
# Dev and benchmark builds also get x86_64 (MIMIR_ABIS, set by Gradle), for the PC's emulators.
abis=${MIMIR_ABIS:-arm64-v8a}
targets=""; for a in ${abis//,/ }; do targets="$targets -t $a"; done
build="export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/29.0.14206865 && \
  CARGO_TARGET_DIR=$target \
  ~/.cargo/bin/cargo ndk $targets -P 28 -o android/app/src/main/jniLibs build --release -p mimir-phone-core"
# Gradle runs this from inside the dev box (before every app build), so the
# app never ships a phone core older than the code.
if [ "${CONTAINER_ID:-}" = "dev" ]; then
    bash -lc "$build"
else
    distrobox enter dev -- bash -lc "cd '$PWD' && $build"
fi
# Only the core itself ships (cargo ndk also copies its dependencies' dylibs).
find android/app/src/main/jniLibs -name '*.so' ! -name 'libmimir_phone_core.so' ! -name 'libstandalone.so' -delete
# A core built from other code (an old name inside) crashes the app on open.
so=android/app/src/main/jniLibs/arm64-v8a/libmimir_phone_core.so
old_name="claude""station"
if ! grep -aq Java_io_github_odnwks_mimir_ "$so" || grep -aq "$old_name" "$so"; then
    echo "phone core doesn't match this app's code: $so" >&2; exit 1
fi
for so in android/app/src/main/jniLibs/*/libmimir_phone_core.so; do
    grep -aq Java_io_github_odnwks_mimir_ "$so" || { echo "phone core doesn't match this app's code: $so" >&2; exit 1; }
done
