#!/usr/bin/env bash
# Builds the phone app's Rust side (crates/phone-core) for Android (arm64, and x86_64 for dev builds)
# into android/app/src/main/jniLibs, in the `dev` build box.
set -euo pipefail
cd "$(dirname "$0")/.."
# Each checkout gets its own build folder: a shared one let another checkout's
# core land in this app (the PC and phone then spoke different messages).
root=${MIMIR_ANDROID_TARGET_ROOT:-/tmp/mimir-build/android-target}
target="$root/$(pwd -P | sha256sum | cut -c1-12)"
# Those folders are gigabytes each, in memory: before a build, other checkouts'
# idle ones are removed, all but the two last used. A build holds a shared lock
# (flock) on its own folder until it ends; only a folder that can be locked
# exclusively is removed. A folder's modification time is when a build last used it.
lock_own() {
    local try
    # Again if the folder was removed while this waited for the lock.
    for try in 1 2 3 4 5; do
        mkdir -p "$target" && exec 9<"$target" && flock -s 9 || return 1
        [ /proc/self/fd/9 -ef "$target" ] && return 0
    done
    return 1
}
remove_old_copies() {
    local name dir idle=0
    while read -r _ name; do
        [[ $name =~ ^[0-9a-f]{12}$ ]] || continue
        dir="$root/$name"
        [ "$dir" != "$target" ] && [ ! -L "$dir" ] || continue
        # Succeeds for an idle copy, kept or removed; the locks end with the subshell.
        (
            exec 8<"$dir" && flock -n -x 8 || exit 1
            # Cargo's own locks too: a checkout with an older script takes no folder lock.
            while IFS= read -r cargo_lock; do
                exec {fd}<"$cargo_lock" && flock -n -x "$fd" || exit 1
            done < <(find "$dir" -maxdepth 3 -name .cargo-lock -type f)
            [ "$idle" -ge 2 ] || exit 0
            rm -rf --one-file-system -- "$dir" || echo "could not remove old build copy $dir" >&2
        ) && idle=$((idle + 1))
    done < <(find "$root" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' | sort -rn)
}
if lock_own; then
    touch "$target" || true
    (remove_old_copies) || echo "could not clean old build copies in $root" >&2
else
    echo "could not lock $target; old build copies are left in place" >&2
fi
# Dev and benchmark builds also get x86_64 (MIMIR_ABIS, set by Gradle), for the PC's emulators.
abis=${MIMIR_ABIS:-arm64-v8a}
# A folder for an ABI not wanted now (an older build) must not be packaged.
for d in android/app/src/main/jniLibs/*/; do
    [ -d "$d" ] || continue
    case ",$abis," in *",$(basename "$d"),"*) ;; *) rm -rf "$d" ;; esac
done
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
touch "$target" 2>/dev/null || true
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
