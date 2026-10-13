#!/usr/bin/env bash
# Verify a dotnet.zip produced by fetch_dotnet_runtime.sh.
#
# A runtime pack is only usable if it is exactly the framework: missing entries
# become FileNotFoundException at run time, and build tooling leaking in inflates
# the APK and the trusted platform assembly list for no benefit. This asserts the
# shape of the pack rather than trying to enumerate every assembly it may contain.
#
# Usage: verify_dotnet_pack.sh <out-dir>   (or pass the zip itself)
set -euo pipefail

TARGET="${1:?usage: verify_dotnet_pack.sh <out-dir-or-zip>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -d "$TARGET" ]; then
    ZIP="$TARGET/dotnet.zip"
    [ -f "$ZIP" ] || { echo "no dotnet.zip in $TARGET" >&2; exit 1; }
else
    ZIP="$TARGET"
fi
unzip -q "$ZIP" -d "$WORK"

COUNT="$(find "$WORK" -maxdepth 1 -name '*.dll' | wc -l)"
FAILURES=0
fail() { echo "  [FAIL] $*"; FAILURES=$((FAILURES + 1)); }
ok()   { echo "  [OK]   $*"; }

echo "============================================================"
echo " dotnet.zip - framework pack check                         "
echo "============================================================"
echo "archive: $ZIP"
echo "entries: $COUNT"
echo ""

# 1. The runtime cannot start without this one.
if [ -f "$WORK/System.Private.CoreLib.dll" ]; then
    ok "System.Private.CoreLib.dll present"
else
    fail "System.Private.CoreLib.dll missing"
fi

# 2. Build tooling must never reach the pack. These are the assemblies that a
#    wide artifacts/bin sweep drags in; they are build-time only and have no
#    business on a device.
TOOLING='^(Microsoft\.CodeAnalysis|Microsoft\.Build|Microsoft\.DotNet\.HotReload|Microsoft\.DotNet\.XHarness|Microsoft\.TestPlatform|xunit|illink|ilasm|Humanizer|AssemblyStripper|StressLogAnalyzer|AndroidTestRunner|NuGet\.|Newtonsoft|Roslynator|StyleCop|StyleCopAnalyzers|Serilog|NodaTime|Mono\.Cecil|Mono\.Options)'
TOOLING_FOUND="$(find "$WORK" -maxdepth 1 -name '*.dll' -printf '%f\n' | grep -E "$TOOLING" || true)"
if [ -n "$TOOLING_FOUND" ]; then
    fail "build tooling leaked into the pack:"
    echo "$TOOLING_FOUND" | sed 's/^/         /'
else
    ok "no build tooling in the pack"
fi

# 3. The framework of a supported .NET release is on the order of 150-220
#    assemblies. A far larger pack means the collection glob is too wide.
if [ "$COUNT" -ge 50 ] && [ "$COUNT" -le 250 ]; then
    ok "assembly count ${COUNT} is in the expected range"
else
    fail "assembly count ${COUNT} is outside the expected 50-250 range"
fi

# 4. A pack built for android-arm64 must not carry desktop-native helper binaries.
#    Native libraries at the top level are expected: the PALs live here as well
#    as in jniLibs, because CoreCLR resolves them from the dotnet directory (5d).
STRAY="$(find "$WORK" -mindepth 2 \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' \) -printf '%P\n' | head -20 || true)"
if [ -n "$STRAY" ]; then
    fail "unexpected nested binaries:"
    echo "$STRAY" | sed 's/^/         /'
else
    ok "flat assembly layout"
fi

# 5. The pack must actually be the android-arm64 BCL.
#
#    This is the check that matters most, and it exists because a pack assembled
#    from the whole artifacts/bin tree can silently contain the BCL of some other
#    RID. Observed on device: a System.Security.Cryptography.dll built for a
#    linux target references System.Security.Cryptography.Native.OpenSsl 234 times
#    and AndroidCryptoNative zero times, so Cpp2IL fails at
#    AssemblyDescriptor.ComputePublicKeyToken with a DllNotFoundException for
#    libSystem.Security.Cryptography.Native.OpenSsl.
#
#    The native PAL decides which P/Invoke targets the BCL binds to, so both
#    halves are asserted here rather than inferred from the pack alone.

# 5a. No managed PAL shim assembly.
#
#     This check used to assert that System.Security.Cryptography.Native.Android.dll
#     was present, which was the wrong shape. .NET ships no managed PAL shim at
#     all: System.Security.Cryptography P/Invokes AndroidCryptoNative_* directly,
#     and the host's own linux-x64 shared framework for 10.0.12 and 11.0.0-rc.1
#     carries no System.Security.Cryptography.Native.*.dll either. So the file
#     this looked for does not exist in a correct android-arm64 pack, and a
#     desktop-RID pack is recognised by carrying one.
shims="$(find "$WORK" -maxdepth 1 -name 'System.Security.Cryptography.Native*.dll' \
    -printf '%f\n' | sort | tr '\n' ' ')"
if [ -n "$shims" ]; then
    fail "pack carries managed PAL shim assemblies: ${shims}"
    fail "  only desktop-RID packs ship these; android-arm64 binds the PAL directly"
else
    ok "no managed PAL shim (android packs bind the PAL by name)"
fi

# 5b. The BCL must bind the Android PAL, not the OpenSSL one. Scanning the
#     metadata catches a wrong-RID pack even when it happens to contain the
#     Android shim file, and the AndroidCryptoNative_ count is the positive
#     signal: those entry points exist only in a CoreCLR-on-Android build.
BCL_CRYPTO="$WORK/System.Security.Cryptography.dll"
if [ -f "$BCL_CRYPTO" ]; then
    openssl_refs="$(grep -c -a -o 'System\.Security\.Cryptography\.Native\.OpenSsl' \
        "$BCL_CRYPTO" 2>/dev/null || true)"
    android_refs="$(grep -c -a -o 'System\.Security\.Cryptography\.Native\.Android' \
        "$BCL_CRYPTO" 2>/dev/null || true)"
    native_refs="$(grep -c -a -o 'AndroidCryptoNative_' \
        "$BCL_CRYPTO" 2>/dev/null || true)"
    if [ "${openssl_refs:-0}" -gt 0 ]; then
        fail "BCL binds the OpenSSL PAL ${openssl_refs} time(s), Android PAL ${android_refs:-0} time(s)"
        fail "  CoreCLR on Android provides AndroidCryptoNative_* via"
        fail "  libSystem.Security.Cryptography.Native.Android.so; an OpenSSL PAL is not"
        fail "  produced by an android-arm64 runtime build and must not be shipped"
    elif [ "${native_refs:-0}" -eq 0 ]; then
        fail "BCL names no AndroidCryptoNative_* entry point (Android PAL ${android_refs:-0} reference(s))"
        fail "  a pack for android-arm64 binds the platform BoringSSL PAL directly;"
        fail "  zero references means this is not that pack"
    else
        ok "BCL binds the Android PAL (${native_refs:-0} AndroidCryptoNative_* P/Invoke target(s))"
    fi
else
    fail "System.Security.Cryptography.dll missing from the pack"
fi

# 5d. The PAL must be inside the pack, not only in the APK's native directory.
#
#     This is a load-time requirement, not a packaging preference. CoreCLR
#     dlopens a shim by bare name and searches NATIVE_DLL_SEARCH_DIRECTORIES,
#     which hosting derives from APP_PATHS — the `core` and `dotnet` per-game
#     directories. The APK's lib/arm64-v8a is not in that set, because libfusion
#     preloads libcoreclr.so with RTLD_NOLOAD and so never establishes the APK
#     directory as a search path.
#
#     Observed on device when this was missing:
#       DllNotFoundException: Unable to load shared library
#         'libSystem.Security.Cryptography.Native.Android'
#       dlopen failed: ".../dotnet/libSystem.Security.Cryptography.Native.Android.so"
#
#     The export count is asserted as well, so a truncated or wrong-RID .so that
#     is present but cannot serve the P/Invoke targets is caught here rather than
#     at interop generation.
PACK_PAL="$WORK/libSystem.Security.Cryptography.Native.Android.so"
if [ ! -f "$PACK_PAL" ]; then
    fail "platform PAL missing from the pack"
    fail "  CoreCLR dlopens it by bare name from the dotnet directory; a copy in"
    fail "  the APK's lib/arm64-v8a is not on that search path"
elif ! command -v readelf >/dev/null 2>&1; then
    ok "platform PAL present in the pack (exports not checked: readelf unavailable)"
else
    pal_exports="$(readelf -sW --dyn-syms "$PACK_PAL" 2>/dev/null |
        awk '$4 == "FUNC" && $7 != "UND" { print $8 }' | sed 's/@.*//' |
        grep -c '^AndroidCryptoNative_' || true)"
    if [ "${pal_exports:-0}" -gt 0 ]; then
        ok "platform PAL present, exports ${pal_exports} AndroidCryptoNative_* entry points"
    else
        fail "pack's platform PAL exports no AndroidCryptoNative_* entry points"
    fi
fi

# 5c. The pack's framework version must match the pinned runtime, otherwise
#     CoreCLR loads a BCL from a different major version.
#
#     Read from TargetFrameworkAttribute (".NETCoreApp,Version=vX.Y"), not the PE
#     file version, which is 4.0.30319 for every framework assembly and says nothing.
#
#     The expectation comes from provenance/dotnet-runtime.env unless it is passed
#     in, so the checked-in pack, CI and this check cannot disagree about which
#     .NET the project is on. That disagreement is what produced a v11.0 pack of
#     unknown RID under a v10.0 pin.
PIN="${ROOT}/provenance/dotnet-runtime.env"
EXPECTED_TFM="${EXPECTED_TFM:-$(sed -n 's/^DOTNET_RUNTIME_EXPECTED_TFM=//p' "$PIN" 2>/dev/null || true)}"

if [ -n "${EXPECTED_TFM:-}" ]; then
    actual_tfm="$(grep -a -o '\.NETCoreApp,Version=v[0-9][0-9]*\.[0-9]*' \
        "$WORK/System.Security.Cryptography.dll" 2>/dev/null |
        sed 's/.*Version=//' | head -1 || true)"
    if [ -z "$actual_tfm" ]; then
        actual_tfm="$(grep -a -o '\.NETCoreApp,Version=v[0-9][0-9]*\.[0-9]*' \
            "$WORK/System.Private.CoreLib.dll" 2>/dev/null |
            sed 's/.*Version=//' | head -1 || true)"
    fi
    if [ -z "$actual_tfm" ]; then
        fail "could not read the pack's TargetFramework"
    elif [ "$actual_tfm" = "${EXPECTED_TFM}" ]; then
        ok "pack targets ${actual_tfm}"
    else
        fail "pack targets ${actual_tfm}, expected ${EXPECTED_TFM}"
    fi
else
    echo "  [SKIP] framework version not checked (set EXPECTED_TFM, or pin it in $PIN)"
fi

echo ""
echo "============================================================"
if [ "$FAILURES" -ne 0 ]; then
    echo " RESULT: FAILED (${FAILURES} problem(s))"
    echo "============================================================"
    exit 1
fi
echo " RESULT: PASSED"
echo "============================================================"