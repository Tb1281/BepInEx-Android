#!/usr/bin/env bash
# Restore and build the patched BepInEx tree from the local feed.
#
# Single implementation for the two jobs that need it (build-bepinex.yml and
# the verify-provenance staging); a restore/build drift between them would
# prove different things about the same queue. Fails closed on version
# substitution: a MonoMod/Il2CppInterop reference that falls back to an
# official release compiles cleanly and fails only on the device, so the
# grep below is load-bearing, not advisory.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

# pipefail is required here, not cosmetic. GitHub runs a `run:` block with
# `bash -e {0}` and no pipefail, so `dotnet restore | tee` would report tee's
# exit status (always 0) and a restore that failed outright would leave an
# empty log, pass the grep below, and print "no version substitution".
set -o pipefail
dotnet restore "$BEPINEX_SRC/BepInEx.sln" -p:SolutionDir="$BEPINEX_SRC/" -v normal 2>&1 |
    tee /tmp/restore.log
# Scoped to the packages this repository builds and owns. A blanket
# NU1602/NU1603 check fails on upstream BepInEx's own pin,
# Samboy063.Cpp2IL.Core 2022.1.0-development.1452, which has never
# resolved and resolves to ...-pre-release.13 instead. That is a real
# upstream condition, it predates the Android port, and every build here -
# including the one tested on device - shipped it. Failing the whole job on
# it would hide the substitutions that matter: ours.
if grep -E 'NU160[23]' /tmp/restore.log | grep -qiE 'MonoMod\.|Il2CppInterop\.'; then
    echo "a package this repository builds fell back to a different version" >&2
    grep -E 'NU160[23]' /tmp/restore.log | grep -iE 'MonoMod\.|Il2CppInterop\.'
    exit 1
fi
echo "no version substitution"

# SolutionDir is pinned so every project writes to one predictable tree
# regardless of whether it is reached through the solution or directly.
dotnet build "$BEPINEX_SRC/Runtimes/Unity/BepInEx.Unity.IL2CPP/BepInEx.Unity.IL2CPP.csproj" \
    -c Release --no-restore -p:GeneratePackageOnBuild=false -p:SolutionDir="$BEPINEX_SRC/"
