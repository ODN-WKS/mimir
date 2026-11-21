#!/usr/bin/env bash
# Creates the Debian 13 build box used by tools/cargo.sh. Safe to re-run.
set -euo pipefail
# grep reads the whole list: with -q it can stop early and fail the pipe.
if ! distrobox list 2>/dev/null | grep '| mimir ' >/dev/null; then
    distrobox create --yes --name mimir --image docker.io/library/debian:13
fi
distrobox enter mimir -- sh -c \
    'sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential pkg-config git'
# The test runner the checks use (one process per test), pinned.
distrobox enter mimir -- sh -c \
    'cargo nextest --version 2>/dev/null | grep "^cargo-nextest 0.9.152 " >/dev/null ||
     cargo install cargo-nextest --version 0.9.152 --locked'
echo "Build box ready."
