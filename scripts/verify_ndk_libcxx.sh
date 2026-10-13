#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - verify the NDK-provided libc++_shared.so
# ==============================================================================
#
# WHY THIS EXISTS
#
# Every other shipped native library is bound to a producer: its bytes are
# compared against the producing run's own manifest (verify_ci_artifacts.sh)
# and its origin is attested (verify_attestation.sh). libc++_shared.so has no
# producing workflow — it is copied from the runner's NDK in build-launcher-apk
# — so both gates skip it by design ([N/A] / deliberately absent). The only
# coverage was the NDK version being pinned in the workflow and zipalign on
# the packaged APK. A substituted file at the NDK path, or a workflow edit
# pointing at a different file, passes every gate and ships.
#
# This closes that boundary: the installed file must match the hash recorded
# in provenance/sha256sums.txt, the same manifest that pins the committed
# copy. The NDK copy and the committed copy are the same bytes (both come
# from NDK 30.0.16248370), so one entry covers both paths.
#
# WHY IT FAILS CLOSED
#
# A missing manifest, a manifest with no libc++ entry, a missing installed
# file, or a hash mismatch all fail. "Cannot check" and "checked" are
# different states and only one of them may pass.
#
# USAGE
#   scripts/verify_ndk_libcxx.sh [installed-file] [manifest]
#
# Defaults: launcher/app/src/main/jniLibs/arm64-v8a/libc++_shared.so and
# provenance/sha256sums.txt, relative to the repository root.
# ==============================================================================

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

INSTALLED="${1:-launcher/app/src/main/jniLibs/arm64-v8a/libc++_shared.so}"
MANIFEST="${2:-provenance/sha256sums.txt}"
WANT_PATH="launcher/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"

if [ ! -f "$MANIFEST" ]; then
    echo "::error::verify_ndk_libcxx: manifest not found: $MANIFEST" >&2
    exit 2
fi

expected="$(awk -v p="$WANT_PATH" '$2 == p { print $1 }' "$MANIFEST")"
if [ -z "$expected" ]; then
    echo "::error::verify_ndk_libcxx: no $WANT_PATH entry in $MANIFEST; refusing to ship an unpinned library." >&2
    exit 1
fi

if [ ! -f "$INSTALLED" ]; then
    echo "  [MISSING]  libc++_shared.so is not installed at $INSTALLED" >&2
    exit 1
fi

actual="$(sha256sum "$INSTALLED" | awk '{print $1}')"
if [ "$actual" != "$expected" ]; then
    echo "  [MISMATCH] libc++_shared.so" >&2
    echo "             expected ${expected}" >&2
    echo "             actual   ${actual}" >&2
    exit 1
fi

echo "  [OK]       libc++_shared.so matches the pinned NDK hash (${actual:0:16}...)"
