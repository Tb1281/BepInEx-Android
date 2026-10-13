#!/usr/bin/env bash
# Rebuild the MonoMod.* packages from pinned upstream + references/monomod-android.patch.
#
# Why this exists, in one paragraph: official MonoMod throws
# NotImplementedException on Android (PlatformTriple.CreateCurrentSystem,
# OSKind.Android), so on this project MonoMod is not an optional dependency but
# the thing that makes Harmony patching work at all. The Android support exists
# as 8 published commits in the public XtraCube fork, on top of an official
# MonoMod commit. We clone the official repository and carry that delta as a
# patch, exactly as scripts/build_il2cppinterop.sh does for Il2CppInterop.
# Nothing is consumed as an opaque nupkg from a feed nobody controls.
#
# This script replaces the previous behaviour, which searched $HOME/.nuget for
# "*-star" packages, warned when they were missing, and carried on. That is
# what let a build succeed against official MonoMod - the build that throws on
# Android - while the shipped assemblies came from the fork.
#
# Usage:
#   scripts/build_monomod.sh [work-dir]
#
# Output: local-nuget/MonoMod.{Core,Utils,RuntimeDetour}.<version>.nupkg
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source provenance/monomod.env

WORK="${1:-${TMPDIR:-/tmp}/opencode/monomod-rebuild}"
PATCH="$ROOT/$PATCH_FILE"
FEED="$ROOT/$LOCAL_FEED"

for var in UPSTREAM_URL UPSTREAM_COMMIT PATCH_FILE FORK_URL FORK_TIP \
           SDK_VERSION MONOMOD_PACKAGE_VERSION LOCAL_FEED MONOMOD_COMPONENTS; do
    [ -n "${!var:-}" ] || { echo "provenance/monomod.env is missing $var" >&2; exit 1; }
done
[ -f "$PATCH" ] || { echo "missing patch: $PATCH" >&2; exit 1; }

# The SDK check happens before the clone so a wrong-SDK failure names the cause
# instead of surfacing as MSB4019 from an imported project.
if [ -x "${DOTNET_ROOT:-}/dotnet" ]; then
    DOTNET="${DOTNET_ROOT}/dotnet"
elif command -v dotnet >/dev/null 2>&1; then
    DOTNET="$(command -v dotnet)"
elif [ -x "$HOME/.dotnet/dotnet" ]; then
    DOTNET="$HOME/.dotnet/dotnet"
else
    echo "no dotnet on PATH; set DOTNET_ROOT or install the SDK" >&2
    exit 1
fi

have_sdk="$("$DOTNET" --list-sdks | awk '{print $1}' | grep -c "^${SDK_VERSION%%.*}\." || true)"
if [ "$have_sdk" -eq 0 ]; then
    echo "MonoMod builds against .NET ${SDK_VERSION%%.*}.x; found:" >&2
    "$DOTNET" --list-sdks >&2
    exit 1
fi

if [ ! -d "$WORK/.git" ]; then
    mkdir -p "$(dirname "$WORK")"
    git clone -q "$UPSTREAM_URL" "$WORK"
fi
cd "$WORK"

# The fork tip is fetched to assert the patch against. It is never checked out
# or built; comparing against it is what makes an edit to the patch that drifts
# from the published Android work a build failure rather than a silent
# behavioural change.
#
# The remote is added *after* cd, deliberately. Adding it before would register
# it in whatever repository the script was invoked from - including this one,
# which is not a MonoMod clone.
if ! git remote get-url fork >/dev/null 2>&1; then
    git remote add fork "$FORK_URL"
fi
git fetch --quiet --depth 1 fork "$FORK_TIP" 2>/dev/null ||
    git fetch --quiet fork

# Never reset a worktree supplied as an argument; it may hold the caller's work.
# A previous invocation's *exact* patch is safe to reuse, and only then.
#
# The check has to cover untracked files as well as diffs: the patch adds two
# new files (Interop/Android.cs, AndroidSystem.cs) that `git diff` does not
# report until they are staged. Comparing only the tracked diff would let a
# half-applied patch pass as "already patched".
# The paths the patch adds as new files. `+++ b/` alone lists every touched
# file, including modified ones, so the added-file marker in the index header is
# what distinguishes them. A patch without that marker (plain `git diff` of two
# trees) yields an empty list, which then has to be compared as empty.
patch_new_files() {
    awk '
        /^new file mode / { added = 1; next }
        /^diff --git /      { added = 0 }
        added && /^\+\+\+ b\// { sub(/^\+\+\+ b\//, ""); print }
    ' "$1" | sort
}

# global.json is this script's to write (see sdk_override below) and is restored
# on exit, but an interrupted run can leave it rewritten. It is therefore not
# treated as caller work: it is reset if it is the only modification.
if [ -f global.json ] && ! git diff --quiet -- global.json 2>/dev/null; then
    git checkout -q -- global.json
fi

status="$(git status --porcelain --untracked-files=normal)"
if [ -n "$status" ]; then
    untracked="$(printf '%s\n' "$status" | awk '$1 == "??" {print $2}' | sort)"
    expected_new="$(patch_new_files "$PATCH")"
    if [ "$(git rev-parse HEAD)" != "$UPSTREAM_COMMIT" ] ||
       ! git apply --reverse --check "$PATCH" ||
       [ "$untracked" != "$expected_new" ]; then
        echo "refusing to overwrite changes in $WORK" >&2
        git status --short >&2
        exit 1
    fi
    echo "reusing previously patched $WORK"
else
    git fetch --quiet origin "$UPSTREAM_COMMIT" ||
        git cat-file -e "$UPSTREAM_COMMIT^{commit}"
    git checkout -q --detach "$UPSTREAM_COMMIT"
    git apply --check "$PATCH"
    git apply --quiet "$PATCH"
    echo "applied $PATCH_FILE to $UPSTREAM_COMMIT"
fi

# Upstream's global.json pins an exact SDK (10.0.100) with
# rollForward=latestPatch, which cannot cross a feature band or a major. Left
# alone it fails on any machine that is not that one exact SDK, before
# compiling anything. Overriding the pin is deliberate and is recorded in
# provenance/monomod.env.
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1

# The patch must still describe the published Android work. Compared as a tree,
# not as a diff, so line-ending or context churn does not matter: what is
# checked is that src/ after the patch equals the fork's src/ at FORK_TIP.
if git cat-file -e "${FORK_TIP}^{commit}" 2>/dev/null; then
    git add -A src/ >/dev/null 2>&1 || true
    if git diff --cached --quiet "$FORK_TIP" -- src/; then
        echo "patched src/ matches the published Android work at ${FORK_TIP:0:8}"
    else
        echo "references/monomod-android.patch no longer reproduces ${FORK_URL}" >&2
        echo "at ${FORK_TIP}: the patched tree differs from the fork's src/." >&2
        git diff --cached --stat "$FORK_TIP" -- src/ >&2
        echo "Either the patch lost a change, or it now carries a deliberate" >&2
        echo "deviation. Record the new base in provenance/monomod.env." >&2
        exit 1
    fi
    git reset -q
else
    echo "warning: could not fetch ${FORK_TIP}; skipping the patch-vs-fork assertion" >&2
fi

# external/iced is a git submodule and is imported as a project by
# MonoMod.Core; without it the build fails with MSB4019 on a missing
# Iced.csproj. This is why a shallow clone of MonoMod alone is not enough.
if [ ! -f external/iced/src/csharp/Intel/Iced/Iced.csproj ]; then
    echo "initialising external/iced submodule"
    git submodule update --init --depth 1 external/iced
fi

# Upstream's global.json pins an exact SDK. The SDK is resolved by the muxer
# from the *project's* directory upwards, not by MSBuild, so --property does not
# redirect it and the file itself has to be rewritten. It is restored by the
# EXIT trap below, and the reuse check above resets it, so the checkout always
# ends up as upstream + our patch and nothing else.
SDK_PIN="$(pwd)/global.json"
restore_sdk_pin() {
    [ -f "$SDK_PIN" ] && git -C "$WORK" checkout -q -- global.json 2>/dev/null || true
}
trap restore_sdk_pin EXIT

sdk_override() {
    cat > "$SDK_PIN" <<JSON
{
  "sdk": {
    "version": "${SDK_VERSION}",
    "rollForward": "latestFeature",
    "allowPrerelease": true
  },
  "msbuild-sdks": {
    "Microsoft.Build.NoTargets": "3.7.56",
    "Microsoft.NET.Sdk.IL": "8.0.0"
  }
}
JSON
}

# Read as "name tfm" pairs. A `for` over $MONOMOD_COMPONENTS would iterate the
# two words separately, which is why the components are read line by line.
while read -r name tfm; do
    [ -n "$name" ] || continue
    project="src/MonoMod.${name}/MonoMod.${name}.csproj"
    [ -f "$project" ] || { echo "missing project: $project" >&2; exit 1; }
    echo "building $name ($tfm)"
    sdk_override
    "$DOTNET" build "$project" -c Release \
        -p:PackageVersion="$MONOMOD_PACKAGE_VERSION" -p:Version="$MONOMOD_PACKAGE_VERSION" \
        -p:GeneratePackageOnBuild=false >/dev/null
done <<EOF
$MONOMOD_COMPONENTS
EOF

# Pack from the built output rather than re-restoring, so the nupkg and the
# assemblies BepInEx compiles against come from one build. Upstream writes to
# artifacts/bin/<project>/release_<tfm>/ rather than the default bin/, which is
# why the gate below compares against that path.
mkdir -p "$FEED"
while read -r name tfm; do
    [ -n "$name" ] || continue
    sdk_override
    "$DOTNET" pack "src/MonoMod.${name}/MonoMod.${name}.csproj" -c Release \
        --no-build \
        -p:PackageVersion="$MONOMOD_PACKAGE_VERSION" -p:Version="$MONOMOD_PACKAGE_VERSION" \
        -o "$FEED" >/dev/null
    pkg="$FEED/MonoMod.${name}.${MONOMOD_PACKAGE_VERSION}.nupkg"
    [ -f "$pkg" ] || { echo "pack produced no $pkg" >&2; exit 1; }

    # The packed asset must be the assembly just built. A pack that silently
    # carried a different MonoMod.Core is the exact failure this script exists
    # to prevent, so it is checked here rather than trusted downstream.
    built="artifacts/bin/MonoMod.${name}/release_${tfm}/MonoMod.${name}.dll"
    [ -f "$built" ] || { echo "missing build output: $built" >&2; exit 1; }
    expected="$(sha256sum "$built" | cut -d' ' -f1)"
    actual="$(unzip -p "$pkg" "lib/${tfm}/MonoMod.${name}.dll" 2>/dev/null | sha256sum | cut -d' ' -f1)"
    [ "$expected" = "$actual" ] || {
        echo "MonoMod.${name}.dll in the package does not match the build:" >&2
        echo "  built  : $expected" >&2
        echo "  package: $actual" >&2
        exit 1
    }
    echo "  MonoMod.${name}.${MONOMOD_PACKAGE_VERSION}.nupkg  (lib/${tfm} matches build)"
done <<EOF
$MONOMOD_COMPONENTS
EOF

# global.json is restored by the EXIT trap.
echo "done: feed at $FEED"
ls -1 "$FEED"