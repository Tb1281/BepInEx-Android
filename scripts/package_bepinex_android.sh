#!/usr/bin/env bash
# Build BepInEx.Android.zip from the patched BepInEx sources.
#
# The archive is a CI output: it is produced from the patched upstream tree
# (cloned and patched by scripts/apply_patches.sh) plus patches/*.patch and
# nothing else. Nothing is carried over from a previously built archive, so its
# contents are determined by the patch queue.
#
# Note that "determined" is not the same as "bit-for-bit reproducible": the managed
# compiler stamps each assembly with the git HEAD it was built from, so the bytes
# depend on which commit of the port is checked out. See provenance/SOURCES.md for
# what the checksum manifest does and does not prove.
#
# Layout mirrors the desktop BepInEx layout the launcher already expects:
#   BepInEx/core/*.dll    managed framework + IL2CPP interop
#   BepInEx/config/*.cfg  configuration
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"
SUB="$BEPINEX_SRC"
OUT="${OUT:-$ROOT/out/BepInEx.Android.zip}"
STAGE="$ROOT/out/bepinex-android"

# This script packages whatever is already built; it does not produce it. Failing
# here on a missing tree is deliberate, because the alternative - `cd` into
# whatever directory happens to exist - is how a stale assembly from another
# source tree gets packaged.
[ -d "$SUB" ] || {
    echo "patched source tree not found: ${SUB#"$ROOT"/}" >&2
    echo "run scripts/apply_patches.sh first" >&2
    exit 1
}

cd "$SUB"
# Two output trees can hold this assembly (the project-local bin/ and the
# solution-level bin/), and `find | head -1` chooses between them by traversal
# order rather than by which one was just built. That silently packaged a stale
# DLL once already, so take the newest instead.
#
# `sed -n '1p'` rather than `head -1`, and this is not a style choice. Under
# `set -euo pipefail`, `head` exits after one line and closes the pipe; if `sort`
# has not finished writing it takes SIGPIPE, prints "write error", and exits 2,
# which pipefail turns into a failed build. `sed -n '1p'` reads the whole stream
# before exiting, so `sort` never sees a closed pipe, and it selects the same
# line - only the failure disappears. Whether it happens depends on how much
# `sort` still has buffered, so it is intermittent: this file packaged correctly
# and failed on consecutive runs of an unchanged commit.
# scripts/test_sort_head_sigpipe.sh reproduces it (40/40 vs 0/40 over 20k lines).
CORE_DLL="$(find . -type f -path '*/bin/Unity.IL2CPP/BepInEx.Unity.IL2CPP.dll' \
    -printf '%T@ %p\n' 2>/dev/null | sort -rn | sed -n '1p' | cut -d' ' -f2-)"
if [ -z "$CORE_DLL" ]; then
    echo "build output not found; run:" >&2
    echo "  dotnet build Runtimes/Unity/BepInEx.Unity.IL2CPP/BepInEx.Unity.IL2CPP.csproj \\" >&2
    echo "      -c Release -p:GeneratePackageOnBuild=false" >&2
    exit 1
fi
CORE_OUT="$(dirname "$CORE_DLL")"

# Refuse to package an assembly older than the sources it was built from.
# Without this a stale bin/ ships and the change only shows up on the device,
# which is exactly where these regressions were being found.
NEWEST_SRC="$(find . -type f \( -name '*.cs' -o -name '*.csproj' \) \
    -printf '%T@ %p\n' 2>/dev/null | sort -rn | sed -n '1p' | cut -d' ' -f2-)"
if [ -n "$NEWEST_SRC" ] && [ "${CORE_DLL}" -ot "${NEWEST_SRC}" ]; then
    echo "refusing to package a stale assembly:" >&2
    echo "  ${CORE_DLL}" >&2
    echo "  is older than ${NEWEST_SRC}" >&2
    exit 1
fi
echo "framework assembly: ${CORE_DLL}"

# NuGet's global cache is keyed by id+version, not by source feed. A stale
# upstream/fork package with the same version once made the APK load the wrong
# InjectorHelpers even while the freshly rebuilt local feed looked correct.
# Both package sets are compared against the assemblies just built. The MonoMod
# check is not redundant with the Il2CppInterop one: MonoMod is what makes
# Harmony patching work on Android at all (official MonoMod throws
# NotImplementedException for OSKind.Android), so a wrong MonoMod here is a
# runtime failure on device that no build step would otherwise catch.

# Explain a mismatch instead of only reporting it.
#
# The two sides of this comparison can each be the stale one, and they need
# opposite fixes, so "these hashes differ" leaves the reader guessing which.
# Measured 2026-10-12: the *built* assembly matched the zip committed four days
# earlier while the freshly rebuilt feed package did not - the build was right
# and ~/.nuget/packages held a four-day-old entry that restore had satisfied
# instead of the package rebuilt moments earlier, because the global cache is
# keyed by id+version and not by feed. Clearing that one directory was the fix;
# no code had changed.
#
# So name both candidates, say which one the cache corroborates, and print the
# exact command. This cannot make the check pass or fail differently - it only
# changes the message on the path that already exits 1.
explain_mismatch() { # explain_mismatch <dll> <built-sha> <feed-sha> <pkg-id> <version> <tfm> <builder-script>
    local dll="$1" built="$2" feed="$3" pkg_id="$4" version="$5" tfm="$6" builder="$7"
    # The cache root is the package directory, not the assembly: the fix is to
    # remove the whole version directory so no stale asset survives for another
    # TFM, and a path pointing at a single .dll reads like a typo and leaves the
    # rest of the cached package in place.
    local pkg_dir="$HOME/.nuget/packages/${pkg_id,,}/$version"
    local cache="$pkg_dir/lib/$tfm/$dll"
    local cache_sha="<absent>"
    [ -f "$cache" ] && cache_sha="$(sha256sum "$cache" | cut -d' ' -f1)"

    echo "stale/wrong $dll: built $built, local feed $feed" >&2
    echo >&2
    if [ "$cache_sha" = "$built" ]; then
        cat >&2 <<EOF
  The built assembly matches the NuGet global cache, so the *local feed* is
  the stale side. The restore resolved ${pkg_id} ${version} from
  ~/.nuget/packages rather than from $LOCAL_FEED, because the global cache is
  keyed by id+version and not by source feed.

    cache : $cache_sha
    feed  : $feed

  Rebuild and clear the cached copy in that order:

    ./scripts/$builder
    rm -rf "$pkg_dir"
    dotnet restore ... && dotnet build ...
EOF
    elif [ "$cache_sha" = "$feed" ]; then
        cat >&2 <<EOF
  The local feed matches the NuGet global cache, so the *built* assembly is
  the stale side - it was produced from a tree other than the one just built,
  or the build did not run after the packages were rebuilt.

    cache : $cache_sha
    built : $built

  Rebuild the managed assemblies before packaging:

    . ./scripts/bepinex_paths.sh
    dotnet build "\$BEPINEX_SRC/Runtimes/Unity/BepInEx.Unity.IL2CPP/BepInEx.Unity.IL2CPP.csproj" \\
      -c Release --no-restore -p:GeneratePackageOnBuild=false -p:SolutionDir="\$BEPINEX_SRC/"
EOF
    else
        cat >&2 <<EOF
  The global cache matches neither side, so both differ from what is on disk.
  That points at more than one stale input rather than a single substitution.

    cache : $cache_sha
    built : $built
    feed  : $feed
EOF
    fi
    echo >&2
    echo "  Refusing to package. Nothing is fixed automatically: deleting a" >&2
    echo "  cache entry is safe, deleting a build output is not this" >&2
    echo "  script's call to make." >&2
}

. "$ROOT/provenance/il2cppinterop.env"
for spec in 'Runtime net10.0' 'Common netstandard2.0' 'HarmonySupport net10.0' 'Generator netstandard2.1'; do
    read -r component tfm <<< "$spec"
    dll="Il2CppInterop.${component}.dll"
    pkg="$ROOT/$LOCAL_FEED/Il2CppInterop.${component}.${PACKAGE_VERSION}.nupkg"
    [ -f "$pkg" ] && [ -f "$CORE_OUT/$dll" ] || {
        echo "missing local package or built assembly for $component" >&2; exit 1;
    }
    expected="$(unzip -p "$pkg" "lib/$tfm/$dll" | sha256sum | cut -d' ' -f1)"
    actual="$(sha256sum "$CORE_OUT/$dll" | cut -d' ' -f1)"
    [ "$expected" = "$actual" ] || {
        explain_mismatch "$dll" "$actual" "$expected" "Il2CppInterop.$component" "$PACKAGE_VERSION" "$tfm" build_il2cppinterop.sh
        exit 1;
    }
done

. "$ROOT/provenance/monomod.env"
while read -r component tfm; do
    [ -n "$component" ] || continue
    dll="MonoMod.${component}.dll"
    pkg="$ROOT/$LOCAL_FEED/MonoMod.${component}.${MONOMOD_PACKAGE_VERSION}.nupkg"
    [ -f "$pkg" ] && [ -f "$CORE_OUT/$dll" ] || {
        echo "missing local package or built assembly for $dll" >&2
        echo "  run scripts/build_monomod.sh" >&2
        exit 1
    }
    expected="$(unzip -p "$pkg" "lib/$tfm/$dll" | sha256sum | cut -d' ' -f1)"
    actual="$(sha256sum "$CORE_OUT/$dll" | cut -d' ' -f1)"
    [ "$expected" = "$actual" ] || {
        explain_mismatch "$dll" "$actual" "$expected" "MonoMod.$component" "$MONOMOD_PACKAGE_VERSION" "$tfm" build_monomod.sh
        exit 1;
    }
done <<EOF
$MONOMOD_COMPONENTS
EOF

rm -rf "$STAGE"
mkdir -p "$STAGE/core" "$STAGE/config"
cp -r "${CORE_OUT}/." "$STAGE/core/"

# The ILSpy-style native helper runtimes (runtimes/<rid>/native/capstone) are
# unreachable here: this launcher is arm64 Android only, the IL2CPP generator
# disassembles with Iced, and no android-arm64 build is published upstream.
rm -rf "${STAGE}/core/runtimes"
cp "$ROOT/launcher/build_assets/BepInEx/config/BepInEx.cfg" "$STAGE/config/BepInEx.cfg"

# The runtime config must name the framework that dotnet.zip actually provides.
# coreclr is started manually by libfusion (not via a hostfxr apphost), so the
# version here is informational; keep it aligned with the runtime pack anyway.
#
# Read from the pin rather than written literally. verify_dotnet_pack.sh only
# inspects dotnet.zip, so a hardcoded tfm here would drift away from the pinned
# runtime without failing any gate -- which is exactly what happened when the
# project moved to .NET 11 and this file kept saying net11.0 through the move back.
PIN="${ROOT}/provenance/dotnet-runtime.env"
[ -f "$PIN" ] || { echo "missing runtime pin: $PIN" >&2; exit 1; }
# shellcheck disable=SC1090
. "$PIN"

RT_TFM="${DOTNET_RUNTIME_TFM:?DOTNET_RUNTIME_TFM missing from $PIN}"
# The framework version is the runtime version's major.minor with a zero patch:
# 10.0.12 -> 10.0.0. The pack's own runtimeconfig names the framework, not the
# servicing band, so this is the value that has to agree with it.
RT_FX="$(printf '%s' "${DOTNET_RUNTIME_VERSION:?DOTNET_RUNTIME_VERSION missing}" |
    cut -d. -f1,2).0"
echo "runtimeconfig: ${RT_TFM} / ${RT_FX}"

cat > "$STAGE/core/BepInEx.Unity.IL2CPP.runtimeconfig.json" <<JSON
{
  "runtimeOptions": {
    "tfm": "${RT_TFM}",
    "framework": {
      "name": "Microsoft.NETCore.App",
      "version": "${RT_FX}"
    },
    "configProperties": {
      "System.Reflection.Metadata.MetadataUpdater.IsSupported": false,
      "System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization": false
    }
  }
}
JSON

rm -f "$OUT"
# Deterministic archive: sorted file-only entries, fixed mtimes and modes.
# `zip -qr` recorded mtimes, walked in filesystem order and wrote explicit
# directory entries, so two runs over identical inputs produced different
# bytes (the 47 vs 49 entry divergence). The helper honours SOURCE_DATE_EPOCH
# and otherwise falls back to the newest input mtime — stable for a given
# tree, unlike the wall clock. Python, not Info-ZIP, so exactly one
# implementation decides the bytes on every host.
python3 "$ROOT/scripts/zip_deterministic.py" "$STAGE" "$OUT" core config

echo "packaged $OUT"
echo "  entries : $(unzip -Z1 "$OUT" | grep -c .)"
echo "  size    : $(du -h "$OUT" | cut -f1)"