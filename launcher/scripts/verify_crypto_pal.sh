#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - crypto PAL provenance check
#
# CoreCLR on Android binds System.Security.Cryptography.Native to the *platform*
# BoringSSL through libSystem.Security.Cryptography.Native.Android.so, which
# exports AndroidCryptoNative_* and reaches the platform crypto through liblog.
#
# Upstream dotnet/runtime never produces the OpenSSL PAL
# (libSystem.Security.Cryptography.Native.OpenSsl.so, exporting CryptoNative_* and
# linked against libcrypto.so/libssl.so). A tree that contains it therefore
# carries a foreign runtime patch, so this check fails the build rather than
# letting a foreign crypto stack in silently.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JNI_DIR="${1:-${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a}"

ANDROID_PAL="libSystem.Security.Cryptography.Native.Android.so"
FOREIGN_LIBS=(
    "libcrypto.so"
    "libssl.so"
    "libSystem.Security.Cryptography.Native.OpenSsl.so"
)

FAILURES=0

echo "============================================================"
echo " BepInEx Android - crypto PAL check                         "
echo "============================================================"

# 1. No foreign OpenSSL artifacts.
echo ""
echo "[1/3] Rejecting foreign OpenSSL runtime artifacts..."
for lib in "${FOREIGN_LIBS[@]}"; do
    if [ -e "${JNI_DIR}/${lib}" ]; then
        echo "  [FOUND]    ${lib}"
        echo "             This binary has no producer in this repository's CI."
        echo "             The Android PAL (BoringSSL via liblog) is used instead."
        FAILURES=$((FAILURES + 1))
    else
        echo "  [ABSENT]   ${lib}"
    fi
done

# 2. The Android PAL must be present and export AndroidCryptoNative_*.
echo ""
echo "[2/3] Checking the Android platform PAL (${ANDROID_PAL})..."
if [ ! -f "${JNI_DIR}/${ANDROID_PAL}" ]; then
    echo "  [MISSING]  ${ANDROID_PAL}"
    FAILURES=$((FAILURES + 1))
else
    exports="$(readelf -sW --dyn-syms "${JNI_DIR}/${ANDROID_PAL}" 2>/dev/null |
        awk '$4 == "FUNC" && $7 != "UND" { print $8 }' | sed 's/@.*//' | sort -u)"
    count="$(printf '%s\n' "${exports}" | grep -c '^AndroidCryptoNative_' || true)"
    if [ "${count}" -gt 0 ]; then
        echo "  [OK]       exports ${count} AndroidCryptoNative_* entry points"
    else
        echo "  [BAD]      exports no AndroidCryptoNative_* entry points"
        FAILURES=$((FAILURES + 1))
    fi

    # It must reach the platform crypto through liblog, not a vendored OpenSSL.
    if readelf -d "${JNI_DIR}/${ANDROID_PAL}" 2>/dev/null | grep -qE 'NEEDED.*\[lib(crypto|ssl)\.so\]'; then
        echo "  [BAD]      DT_NEEDED pulls in libcrypto/libssl"
        FAILURES=$((FAILURES + 1))
    else
        echo "  [OK]       no libcrypto/libssl dependency"
    fi
fi

# 3. CoreCLR must not drag in an OpenSSL dependency either.
echo ""
echo "[3/3] Checking libcoreclr.so for OpenSSL linkage..."
CORECLR="${JNI_DIR}/libcoreclr.so"
if [ ! -f "${CORECLR}" ]; then
    echo "  [MISSING]  libcoreclr.so"
    FAILURES=$((FAILURES + 1))
elif readelf -d "${CORECLR}" 2>/dev/null | grep -qE 'NEEDED.*\[lib(crypto|ssl)\.so\]'; then
    echo "  [BAD]      libcoreclr.so DT_NEEDED references libcrypto/libssl"
    FAILURES=$((FAILURES + 1))
else
    echo "  [OK]       libcoreclr.so has no libcrypto/libssl dependency"
fi

echo ""
echo "============================================================"
if [ "${FAILURES}" -ne 0 ]; then
    echo " RESULT: FAILED (${FAILURES} problem(s))"
    echo "============================================================"
    exit 1
fi
echo " RESULT: PASSED - platform BoringSSL PAL only."
echo "============================================================"