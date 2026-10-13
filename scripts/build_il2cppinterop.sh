#!/usr/bin/env bash
# Rebuild Il2CppInterop.* 1.5.2 from the pinned upstream commit + our ARM64/Disarm patch.
#
# The package is a fork (Android libil2cpp.so / ARM64 xref scanning). The fork
# sources are lost; this script regenerates them from the public upstream HEAD
# plus the behaviour delta carried in references/il2cppinterop-arm64-disarm.patch.
#
# Usage:
#   scripts/build_il2cppinterop.sh [upstream-checkout-dir]
#
# Output: local-nuget/*.nupkg
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
source provenance/il2cppinterop.env

# The SDK this tree builds with. Read from the root global.json — the single
# canonical pin (CI's setup-dotnet and the BepInEx build reference the same
# file) — not duplicated into il2cppinterop.env, where it would be a second
# list that can disagree.
SDK_VERSION="$(python3 -c "import json; print(json.load(open('$ROOT/global.json'))['sdk']['version'])")"
[ -n "$SDK_VERSION" ] || { echo "root global.json has no sdk.version" >&2; exit 1; }

# Fail before the clone so a wrong-SDK setup names the cause instead of
# surfacing as a strange MSBuild error halfway through the pack.
if command -v dotnet >/dev/null 2>&1; then
    DOTNET_BIN="$(command -v dotnet)"
elif [ -x "$HOME/.dotnet/dotnet" ]; then
    DOTNET_BIN="$HOME/.dotnet/dotnet"
else
    echo "no dotnet on PATH; install the .NET SDK" >&2
    exit 1
fi

have_sdk="$("$DOTNET_BIN" --list-sdks | awk '{print $1}' | grep -c "^${SDK_VERSION%%.*}\." || true)"
if [ "$have_sdk" -eq 0 ]; then
    echo "Il2CppInterop builds against .NET ${SDK_VERSION%%.*}.x (root global.json); found:" >&2
    "$DOTNET_BIN" --list-sdks >&2
    exit 1
fi

WORK="${1:-${TMPDIR:-/tmp}/opencode/il2cppinterop-rebuild}"
PATCH="$ROOT/$PATCH_FILE"
if [ ! -f "$PATCH" ]; then
    echo "missing patch: $PATCH" >&2
    exit 1
fi

if [ ! -d "$WORK/.git" ]; then
    mkdir -p "$(dirname "$WORK")"
    git clone -q "$UPSTREAM_URL" "$WORK"
fi
cd "$WORK"

# An interrupted run can leave our SDK override (marker below) written into
# this tree. It is ours, not upstream's and not the caller's, so rebuilding
# the patched state from HEAD plus the patch's own global.json hunk is
# restoration — and anything else modified is still refused loudly below.
# A global.json modified WITHOUT our marker is the caller's: left untouched,
# and the reuse check refuses it exactly as it always has.
GLOBAL_JSON_MARKER="_bepinex"
if grep -q "$GLOBAL_JSON_MARKER" global.json 2>/dev/null; then
    git checkout -q -- global.json
    git apply --include=global.json "$PATCH"
fi
# Never reset an arbitrary worktree supplied as an argument: it may contain
# the caller's changes. A previous invocation's *exact* patch is safe to reuse.
if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
    if [ "$(git rev-parse HEAD)" != "$UPSTREAM_COMMIT" ] ||
       ! git apply --reverse --check "$PATCH" ||
       ! cmp -s <(git diff --binary) "$PATCH"; then
        echo "refusing to overwrite changes in $WORK" >&2
        exit 1
    fi
    echo "reusing previously patched $WORK"
else
    git fetch --quiet origin "$UPSTREAM_COMMIT" ||
        git cat-file -e "$UPSTREAM_COMMIT^{commit}"
    git checkout -q --detach "$UPSTREAM_COMMIT"
    git apply --check "$PATCH"
    git apply --quiet "$PATCH"
fi

# NuGet lock files, vendored in references/nuget-locks and staged here like
# apply_patches.sh does for the BepInEx tree. Absence is fatal: an unlocked
# restore silently floats transitive versions per restore, which is how
# identical pins compiled to different assemblies on different machines.
LOCK_SRC="$ROOT/references/nuget-locks/il2cppinterop"
[ -d "$LOCK_SRC" ] || { echo "missing NuGet lock directory: $LOCK_SRC" >&2; exit 1; }
cp -r "$LOCK_SRC/." "$WORK/"

# Upstream's global.json pins SDK 8.0.0 with rollForward=latestMajor, so the
# muxer resolves to the newest installed major — .NET 11 RC on any machine
# that has one, while CI (10.0.401 only) builds the same tree with 10.0.401.
# Measured 2026-10-12: the local feed was compiled by 11.0.100-rc
# (NETSDK1057 in the log) with no error anywhere to show for it. Overriding
# the pin is deliberate, mirroring scripts/build_monomod.sh: latestFeature
# stays within major 10, so this resolves to exactly $SDK_VERSION wherever it
# is installed and fails loudly where it is not.
#
# Saved and restored byte-for-byte (not via git checkout, which would drop
# the patch's own global.json hunk): the tree ends up as upstream + our patch
# exactly as the reuse check above expects, so the next run reuses it.
SAVED_GLOBAL_JSON="$(cat "$WORK/global.json")"
SDK_PIN="$WORK/global.json"
restore_sdk_pin() {
    printf '%s\n' "$SAVED_GLOBAL_JSON" > "$SDK_PIN" 2>/dev/null || true
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
  "$GLOBAL_JSON_MARKER": "SDK override, restored on exit"
}
JSON
}

sdk_override
dotnet restore --verbosity minimal -p:Version="$PACKAGE_VERSION" -p:RestoreLockedMode=true
sdk_override
dotnet build -c Release --no-restore -p:Version="$PACKAGE_VERSION"
for proj in Il2CppInterop.Common Il2CppInterop.Runtime Il2CppInterop.HarmonySupport Il2CppInterop.Generator; do
    sdk_override
    dotnet pack "$proj/$proj.csproj" -c Release --no-build -p:Version="$PACKAGE_VERSION" -o "$ROOT/$LOCAL_FEED"
done

# MonoMod is NOT handled here. It used to be: this script searched
# $HOME/.nuget for "*-star" packages, warned when they were missing, and carried
# on. That is how a build came to compile against official MonoMod - the build
# whose PlatformTriple.CreateCurrentSystem throws NotImplementedException on
# OSKind.Android - while the shipped assemblies were built from the fork. The
# only trace was an NU1603 warning in a log nobody reads.
#
# scripts/build_monomod.sh rebuilds those packages from official MonoMod plus
# references/monomod-android.patch; scripts/package_bepinex_android.sh then
# compares them against the assemblies BepInEx compiled against, and fails if
# they are not the same build. Run it before this script.

echo "done: feed at $ROOT/$LOCAL_FEED"
ls -1 "$ROOT/$LOCAL_FEED"
