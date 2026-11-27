#!/usr/bin/env bash
# Tests the removal of old phone-core build copies in tools/build-phone-core.sh.
# Runs a copy of the script in a throwaway checkout with a stand-in cargo; the
# copy's built-in folder is rewritten, so the real one can never be reached.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd -P)
t=$(mktemp -d "${TMPDIR:-/tmp}/mimir-build-clean.XXXXXX")
holders=()
cleanup() {
    for p in "${holders[@]}"; do kill "$p" 2>/dev/null; done
    chmod -R u+w "$t" 2>/dev/null
    rm -rf "$t"
}
trap cleanup EXIT
root="$t/root"
co="$t/checkout"
mkdir -p "$co/tools" "$t/home/.cargo/bin" "$t/bin"
sed "s#/tmp/mimir-build/android-target#$t/not-overridden#g" "$here/build-phone-core.sh" > "$co/tools/build-phone-core.sh"
own=$(printf '%s\n' "$co" | sha256sum | cut -c1-12)

# Stand-in cargo: notes whether its own folder is locked, then makes the core.
cat > "$t/home/.cargo/bin/cargo" <<STUB
#!/usr/bin/env bash
if [ -d "\$CARGO_TARGET_DIR" ]; then
    if flock -n -x "\$CARGO_TARGET_DIR" true; then echo free; else echo locked; fi > "$t/during"
fi
mkdir -p android/app/src/main/jniLibs/arm64-v8a
echo Java_io_github_odnwks_mimir_ > android/app/src/main/jniLibs/arm64-v8a/libmimir_phone_core.so
STUB
# Stand-in distrobox, for a run from outside the box.
cat > "$t/bin/distrobox" <<'STUB'
#!/usr/bin/env bash
shift 3
exec "$@"
STUB
chmod +x "$t/home/.cargo/bin/cargo" "$t/bin/distrobox"

fails=0
check() { # check <what> <command...>
    local what=$1; shift
    if "$@"; then echo "ok   $what"; else echo "FAIL $what"; fails=$((fails+1)); fi
}
there() { [ -e "$root/$1" ]; }
gone() { [ ! -e "$root/$1" ] && [ ! -L "$root/$1" ]; }
build() { # build [box name]
    rm -f "$t/during"
    env HOME="$t/home" PATH="$t/bin:$PATH" CONTAINER_ID="${1-dev}" MIMIR_ABIS=arm64-v8a \
        MIMIR_ANDROID_TARGET_ROOT="$root" bash "$co/tools/build-phone-core.sh" > "$t/out" 2>&1
}
copy() { # copy <name> <minutes since last used>
    mkdir -p "$root/$1/release"
    echo x > "$root/$1/release/file"
    touch -d "-$2 minutes" "$root/$1"
}
reset() { chmod -R u+w "$root" 2>/dev/null; rm -rf "$root"; mkdir -p "$root"; }
hold() { # hold <path>: another process holds a lock on it
    flock -x "$1" sleep 60 & holders+=($!)
    while flock -n -x "$1" true; do sleep 0.05; done
}
release() { for p in "${holders[@]}"; do pkill -P "$p" 2>/dev/null; wait "$p" 2>/dev/null; done; holders=(); }

echo "# more than two idle copies"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30; copy aaaaaaaaaaa4 40
check "build succeeds" build
check "newest idle copy kept" there aaaaaaaaaaa1
check "second newest kept" there aaaaaaaaaaa2
check "third removed" gone aaaaaaaaaaa3
check "fourth removed" gone aaaaaaaaaaa4
check "own folder locked during the build" grep -qx locked "$t/during"

echo "# a build from outside the box"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30
check "build succeeds" build ""
check "two newest kept" there aaaaaaaaaaa2
check "third removed" gone aaaaaaaaaaa3
check "own folder locked during the build" grep -qx locked "$t/during"

echo "# copies being built"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30
copy bbbbbbbbbbb1 50; copy bbbbbbbbbbb2 60
mkdir -p "$root/bbbbbbbbbbb2/aarch64-linux-android/release"
: > "$root/bbbbbbbbbbb2/aarch64-linux-android/release/.cargo-lock"
touch -d "-60 minutes" "$root/bbbbbbbbbbb2"
hold "$root/bbbbbbbbbbb1"                                              # this script's lock
hold "$root/bbbbbbbbbbb2/aarch64-linux-android/release/.cargo-lock"    # cargo's, from an older script
check "build succeeds" build
check "locked copy kept" there bbbbbbbbbbb1
check "copy cargo is building in kept" there bbbbbbbbbbb2
check "two newest idle kept" there aaaaaaaaaaa2
check "third idle removed" gone aaaaaaaaaaa3
release

echo "# own folder"
reset
copy "$own" 500; copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30
check "build succeeds" build
check "own folder kept, with its contents" there "$own/release/file"
check "own does not count as one of the two" there aaaaaaaaaaa2
check "third other removed" gone aaaaaaaaaaa3
check "own folder marked as just used" test -n "$(find "$root/$own" -maxdepth 0 -mmin -2)"

echo "# odd names and links"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30
for n in keep-me aaaaaaaaaaa aaaaaaaaaaaaa AAAAAAAAAAAA gggggggggggg "aaaaaaaaaaa1 old" .hidden; do copy "$n" 900; done
echo x > "$root/cccccccccccc"; touch -d "-900 minutes" "$root/cccccccccccc"
mkdir -p "$t/outside"; echo x > "$t/outside/precious"
ln -s "$t/outside" "$root/dddddddddddd"; touch -h -d "-900 minutes" "$root/dddddddddddd"
ln -s "$t/outside" "$root/aaaaaaaaaaa3/link"; touch -d "-30 minutes" "$root/aaaaaaaaaaa3"
check "build succeeds" build
check "third removed" gone aaaaaaaaaaa3
for n in keep-me aaaaaaaaaaa aaaaaaaaaaaaa AAAAAAAAAAAA gggggggggggg "aaaaaaaaaaa1 old" .hidden cccccccccccc dddddddddddd; do
    check "left alone: $n" there "$n"
done
check "nothing behind a link removed" test -f "$t/outside/precious"

echo "# a copy that cannot be removed"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30; copy aaaaaaaaaaa4 40
chmod a-w "$root/aaaaaaaaaaa3/release"
check "build still succeeds" build
check "the failure is reported" grep -q "could not remove" "$t/out"
check "the others are still removed" gone aaaaaaaaaaa4

echo "# locking does not work at all"
reset
copy aaaaaaaaaaa1 10; copy aaaaaaaaaaa2 20; copy aaaaaaaaaaa3 30
printf '#!/bin/sh\nexit 1\n' > "$t/bin/flock"; chmod +x "$t/bin/flock"
check "build still succeeds" build
check "nothing removed without a lock" there aaaaaaaaaaa3
rm "$t/bin/flock"

if [ "$fails" -ne 0 ]; then echo "$fails check(s) failed"; exit 1; fi
echo "all passed"
