#!/usr/bin/env bash
# Fetch the pinned official .NET runtime pack and turn it into what the APK ships.
#
# The pack is one Microsoft package containing all four runtime-side artifacts:
#
#   runtimes/android-arm64/native/*.so   -> jniLibs/arm64-v8a  (libcoreclr.so,
#                                          libclrjit.so, the platform PAL shims)
#   runtimes/android-arm64/lib/$TFM/*.dll -> dotnet.zip         (the BCL)
#
# Taking them from one package is the point. The managed framework and the native
# engine have to come from the same runtime build or P/Invoke entry points
# disappear at load time with nothing failing at build time, so they are not
# assembled from separate sources here.
#
# The version and the expected SHA-256 live in provenance/dotnet-runtime.env.
# The hash is checked before anything is extracted: an unpinned or unexpected
# package must fail loudly rather than land in the tree.
#
# Usage: fetch_dotnet_runtime.sh <out-dir>
#   out-dir receives native/, bcl/ and dotnet.zip, and is verified before the
#   script exits.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:?usage: fetch_dotnet_runtime.sh <out-dir>}"
PIN="${ROOT}/provenance/dotnet-runtime.env"

# shellcheck disable=SC1090
[ -f "$PIN" ] || { echo "missing version pin: $PIN" >&2; exit 1; }
. "$PIN"

FEED="${DOTNET_RUNTIME_FEED:-https://api.nuget.org/v3-flatcontainer}"
NUPKG="${DOTNET_RUNTIME_PACK}.${DOTNET_RUNTIME_VERSION}.nupkg"
URL="${FEED}/${DOTNET_RUNTIME_PACK}/${DOTNET_RUNTIME_VERSION}/${NUPKG}"

echo "============================================================"
echo " fetch .NET runtime pack                                  "
echo "============================================================"
echo "pack   : ${DOTNET_RUNTIME_PACK}"
echo "version: ${DOTNET_RUNTIME_VERSION}"
echo "tfm    : ${DOTNET_RUNTIME_TFM}"
echo "source : ${URL}"
echo ""

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. Download.
echo "[1/4] Downloading the package..."
if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --retry 3 -o "$WORK/$NUPKG" "$URL"
else
    wget -q -O "$WORK/$NUPKG" "$URL"
fi
echo "        $(du -h "$WORK/$NUPKG" | cut -f1)"

# 2. Verify the bytes before looking inside. This is the trust anchor: the
#    version pin says which runtime, the hash says which bytes.
echo "[2/4] Verifying SHA-256 against the pin..."
actual="$(sha256sum "$WORK/$NUPKG" | awk '{print $1}')"
if [ "$actual" != "$DOTNET_RUNTIME_PACK_SHA256" ]; then
    echo "  [FAIL] SHA-256 mismatch for ${NUPKG}" >&2
    echo "         expected ${DOTNET_RUNTIME_PACK_SHA256}" >&2
    echo "         actual   ${actual}" >&2
    exit 1
fi
echo "  [OK]   ${actual}"

# 3. Unpack. Only the two runtimes/<rid> subtrees are of interest; the rest of a
#    runtime pack is ref/lib assets and per-RIFX metadata that has no business
#    on a device.
echo "[3/4] Extracting native runtime and framework assemblies..."
unzip -q "$WORK/$NUPKG" -d "$WORK/pkg"

SRC_NATIVE="$WORK/pkg/runtimes/android-arm64/native"
SRC_BCL="$WORK/pkg/runtimes/android-arm64/lib/${DOTNET_RUNTIME_TFM}"

[ -d "$SRC_NATIVE" ] || { echo "  [FAIL] no runtimes/android-arm64/native in the pack" >&2; exit 1; }
[ -d "$SRC_BCL" ]    || { echo "  [FAIL] no lib/${DOTNET_RUNTIME_TFM} in the pack" >&2; exit 1; }

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/native" "$OUT_DIR/bcl"

# -p: the deterministic zipper falls back to the newest input mtime, so plain
# cp (which stamps the copy with the wall clock) would make every fetch
# produce a different dotnet.zip. Preserving the pack entry dates keeps the
# archive a function of the pinned pack, not of when it was fetched.
cp -p "$SRC_NATIVE"/*.so "$OUT_DIR/native/"
cp -p "$SRC_BCL"/*.dll "$OUT_DIR/bcl/"

# The pack also carries libSystem.Security.Cryptography.Native.Android.jar, which is
# the Java contract the crypto PAL looks up at JNI_OnLoad. That contract moves
# between .NET releases -- .NET 11 wants DotnetProxyTrustManager(long,
# X509TrustManager, String) and verifyRemoteCertificate(long, String), .NET 10 wants
# the one-argument forms -- and a mismatch aborts the game process rather than
# failing a build. Regenerate the expectation from this pack so a runtime bump updates
# it instead of leaving a stale literal behind in a check.
#
# Done before the archive is built so it does not depend on `zip` being installed.
# The jar is not copied into dotnet.zip: the runtime dlopens the native PAL, never the
# Java classes, which come from the launcher's own dex.
echo "[3b/4] Regenerating the crypto PAL Java contract..."
PAL_JAR="$SRC_NATIVE/libSystem.Security.Cryptography.Native.Android.jar"
if [ -f "$PAL_JAR" ]; then
    "$ROOT/scripts/gen_pal_java_contract.sh" "$PAL_JAR" \
        "${ROOT}/provenance/pal-java-contract.txt"
else
    echo "  [FAIL] no libSystem.Security.Cryptography.Native.Android.jar in the pack" >&2
    echo "         the Java contract cannot be verified without it" >&2
    exit 1
fi

# The platform PALs go into dotnet.zip as well as into jniLibs.
#
# CoreCLR's PAL loader resolves a shim by bare name (dlopen
# "libSystem.Security.Cryptography.Native.Android.so") and searches the
# directories named by NATIVE_DLL_SEARCH_DIRECTORIES, which hosting derives from
# APP_PATHS — here, `core` and `dotnet`. The APK's lib/arm64-v8a is not among
# them: libcoreclr.so is already resident because libfusion preloads it with
# RTLD_NOLOAD, so nothing else pulls the APK's native directory into the search
# path.
#
# Observed on device with the PAL present only in jniLibs:
#   DllNotFoundException: Unable to load shared library
#   'libSystem.Security.Cryptography.Native.Android'
#   dlopen failed: ".../dotnet/libSystem.Security.Cryptography.Native.Android.so"
# The BCL and the PAL must be in the same directory tree, so dotnet.zip carries
# them and the dotnet directory is a self-contained runtime.
#
# These are byte-identical copies of the files in jniLibs — one package, so
# there is no second version to keep in step.
cp -p "$SRC_NATIVE"/*.so "$OUT_DIR/bcl/"

# The launcher points coreclr hosting at TRUSTED_PLATFORM_ASSEMBLIES and
# SYSTEM_CORELIB_DIRECTORY, so the runtime never resolves the framework through a
# runtimeconfig. The pack's own runtimeconfig still ships in dotnet.zip: it is the
# framework identity of exactly these assemblies, and having it in the per-game
# dotnet directory keeps that directory self-describing.
for extra in Microsoft.NETCore.App.runtimeconfig.json Microsoft.NETCore.App.deps.json; do
    [ -f "$SRC_BCL/$extra" ] && cp -p "$SRC_BCL/$extra" "$OUT_DIR/bcl/"
done

# Deterministic archive (see scripts/zip_deterministic.py): sorted file-only
# entries with fixed mtimes and modes. `zip -qr` recorded mtimes and walked in
# filesystem order, so the same BCL produced different bytes on every fetch.
# The "." relpath collects every file at top level without writing an explicit
# directory entry for it.
python3 "${ROOT}/scripts/zip_deterministic.py" "$OUT_DIR/bcl" "$OUT_DIR/dotnet.zip" .

# 4. The pack is only usable if it is the framework for this RID. This is the
#    check that catches a pack whose BCL binds a desktop PAL, which otherwise
#    fails on device inside Cpp2IL rather than here.
echo "[4/4] Verifying the framework pack..."
echo "native : $(ls "$OUT_DIR/native" | wc -l) libraries"
echo "bcl    : $(ls "$OUT_DIR/bcl"/*.dll | wc -l) assemblies"
echo "pack   : $OUT_DIR/dotnet.zip ($(du -h "$OUT_DIR/dotnet.zip" | cut -f1))"
echo ""
"${ROOT}/scripts/verify_dotnet_pack.sh" "$OUT_DIR"