#!/usr/bin/env bash
# Fixture harness for scripts/verify_ndk_libcxx.sh.
#
# libc++_shared.so is the one shipped binary with no producing workflow, so
# both the hash gate ([N/A]) and the attestation gate (deliberately absent)
# skip it. This gate must therefore refuse everything it cannot vouch for:
#
#   - a matching file PASSES.
#   - a tampered file FAILS with [MISMATCH] and names the file.
#   - a missing installed file FAILS with [MISSING].
#   - a manifest with no libc++ entry FAILS rather than passing vacuously.
#   - a missing manifest file FAILS rather than skipping.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/ndk-libcxx.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/verify_ndk_libcxx.sh"

# Minimal manifest + installed file, both under the fixture tree. The script
# takes explicit paths so the real tree is never touched.
setup() {
    rm -rf "$FIX/tree"
    mkdir -p "$FIX/tree"
    printf 'ndk-libcpp-bytes\n' > "$FIX/tree/libc++_shared.so"
    local hash
    hash="$(sha256sum "$FIX/tree/libc++_shared.so" | awk '{print $1}')"
    printf '%s  launcher/app/src/main/jniLibs/arm64-v8a/libc++_shared.so\n' "$hash" > "$FIX/tree/sha256sums.txt"
}

fails=0
run() { # run <want-exit> <label> [extra args...]
    local want="$1" label="$2"
    shift 2
    local out rc
    out="$("$SCRIPT" "$@" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1))
        return
    fi
    echo "ok    $label (exit $rc)"
}

echo "== verify_ndk_libcxx.sh =="

setup; run 0 "matching file passes" "$FIX/tree/libc++_shared.so" "$FIX/tree/sha256sums.txt"

setup; printf 'tampered\n' >> "$FIX/tree/libc++_shared.so"
out="$("$SCRIPT" "$FIX/tree/libc++_shared.so" "$FIX/tree/sha256sums.txt" 2>&1)"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -qF "[MISMATCH]"; then
    echo "ok    tampered file fails with [MISMATCH]"
else
    echo "FAIL  tampered file should fail with [MISMATCH] (rc=$rc)"; fails=$((fails + 1))
fi

setup; rm "$FIX/tree/libc++_shared.so"
out="$("$SCRIPT" "$FIX/tree/libc++_shared.so" "$FIX/tree/sha256sums.txt" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "[MISSING]"; then
    echo "ok    missing file fails with [MISSING]"
else
    echo "FAIL  missing file should fail with [MISSING] (rc=$rc)"; fails=$((fails + 1))
fi

setup; printf '# no entries\n' > "$FIX/tree/sha256sums.txt"
run 1 "manifest with no libc++ entry fails" "$FIX/tree/libc++_shared.so" "$FIX/tree/sha256sums.txt"

setup; run 2 "missing manifest fails" "$FIX/tree/libc++_shared.so" "$FIX/tree/does-not-exist.txt"

# The real tree is the acceptance case: the committed hash must describe the
# NDK file this machine ships. Not a stub — the actual manifest and bytes.
out="$("$SCRIPT" 2>&1)"; rc=$?
if [ "$rc" -eq 0 ]; then
    echo "ok    real tree: installed libc++ matches the pinned manifest"
else
    echo "FAIL  real tree: installed libc++ does not match the manifest (rc=$rc)"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
