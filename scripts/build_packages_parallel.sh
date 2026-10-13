#!/usr/bin/env bash
# Build the MonoMod and Il2CppInterop Android packages concurrently.
#
# WHY PARALLEL, AND WHY IT IS SAFE
#
# The two package sets are independent inputs to the same restore: MonoMod
# carries the Android patching support (official MonoMod throws
# NotImplementedException on Android), Il2CppInterop carries the ARM64 loader
# and Disarm xref adaptation. Neither consumes the other's output. Each builds
# in its own scratch clone (`$TMPDIR/...-rebuild`, one per script), writes
# distinct `.nupkg` filenames into `local-nuget/`, and restores its own
# `global.json` via an EXIT trap inside its own tree. The shared states are
# `~/.nuget/packages` (concurrent restores are NuGet's normal operating mode —
# machine-wide cache with extraction locking) and CPU (each `dotnet build`
# already parallelises, so two builds oversubscribe; the win comes from the
# serial phases — clone, restore, pack — overlapping, not from halving).
#
# WHAT IS PRESERVED
#
# Both package sets must exist before `dotnet restore`, or NuGet substitutes
# the official releases with only an NU1602/3 warning — the silent failure
# every build step here exists to stop. This helper waits for BOTH scripts and
# exits non-zero if EITHER failed, so "both present" still holds exactly as
# with the sequential steps it replaces. Logs are written to separate files
# while running (no interleaving) and printed serially afterwards in a fixed
# order, so the console record is complete and reviewable.
#
# ISOLATED TMPDIR PER BUILD
#
# The two dotnet processes must not share a temp directory. Beyond
# ~/.nuget/packages, dotnet uses $TMPDIR/.dotnet/shm for cross-process shared
# memory, and two concurrent processes racing to mkdir the same session
# directory fail with EEXIST (System.IO.IOException: 'NuGet-Migrations').
# Each build therefore gets its own TMPDIR under the log dir; the scripts
# already honor TMPDIR for their scratch clones.
#
# Usage: build_packages_parallel.sh [monomod-script] [interop-script]
#   Defaults are scripts/build_monomod.sh and scripts/build_il2cppinterop.sh
#   under the repo root. Explicit paths are the fixture seam: the fixture
#   drives the parallel logic with fast stubs instead of real dotnet builds.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MONO="${1:-$ROOT/scripts/build_monomod.sh}"
INTEROP="${2:-$ROOT/scripts/build_il2cppinterop.sh}"

for s in "$MONO" "$INTEROP"; do
    if [ ! -x "$s" ]; then
        echo "build_packages_parallel: not executable: $s" >&2
        exit 1
    fi
done

LOGDIR="$(mktemp -d "${TMPDIR:-/tmp}/package-build-logs.XXXXXX")"
trap 'rm -rf "$LOGDIR"' EXIT
MONO_LOG="$LOGDIR/monomod.log"
INTEROP_LOG="$LOGDIR/il2cppinterop.log"

# Separate TMPDIRs: dotnet's shm session directory collides when two dotnet
# processes share $TMPDIR (EEXIST on mkdir). The parent TMPDIR is restored
# for the wait/print phase below, which does not invoke dotnet.
MONO_TMP="$LOGDIR/mono-tmp"
INTEROP_TMP="$LOGDIR/interop-tmp"
mkdir -p "$MONO_TMP" "$INTEROP_TMP"

echo "building both package sets concurrently..."
TMPDIR="$MONO_TMP" "$MONO" >"$MONO_LOG" 2>&1 &
MONO_PID=$!
TMPDIR="$INTEROP_TMP" "$INTEROP" >"$INTEROP_LOG" 2>&1 &
INTEROP_PID=$!

mono_rc=0; interop_rc=0
wait "$MONO_PID" || mono_rc=$?
wait "$INTEROP_PID" || interop_rc=$?

# Serial record, fixed order: the full output of both builds is always in the
# log, whether the build passed or not. A parallel step that prints only tails
# on success hides the warnings (NU1602/3) this repository treats as errors.
echo "==================== MonoMod ===================="
cat "$MONO_LOG"
echo "==================== Il2CppInterop ===================="
cat "$INTEROP_LOG"

fails=0
[ "$mono_rc" -eq 0 ] || { echo "FAILED: $MONO (exit $mono_rc)" >&2; fails=$((fails + 1)); }
[ "$interop_rc" -eq 0 ] || { echo "FAILED: $INTEROP (exit $interop_rc)" >&2; fails=$((fails + 1)); }

if [ "$fails" -eq 0 ]; then
    echo "both package sets built"
else
    echo "$fails package build(s) failed; local-nuget is incomplete, refusing to continue" >&2
    exit 1
fi
