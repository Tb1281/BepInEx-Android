#!/usr/bin/env bash
# Fixture: scripts/restore_and_build.sh restores before building and refuses
# version substitution.
#
# WHY THIS EXISTS
#
# The script is the single implementation behind three callers
# (build-bepinex.yml, the verify-provenance patch-queue job, and the
# provenance staging), so a regression in it breaks the managed build
# everywhere at once. Its whole value is two refusals: a restore that fails
# must not read as success through the `| tee` pipe, and a
# MonoMod/Il2CppInterop reference that falls back to an official release
# must fail here rather than compile cleanly and die only on the device.
# Upstream BepInEx's own unresolvable Cpp2IL pin must keep passing, or the
# scoped check has become a blanket one.
#
# HOW IT RUNS WITHOUT A TOOLCHAIN
#
# A stub `dotnet` on PATH records its argv and replays canned restore logs.
# BEPINEX_SRC points at an empty temp dir; the stub never reads it. Cases:
# clean tree passes with restore before build; NU1602/NU1603 naming our
# packages fail; the same warnings naming only upstream Cpp2IL pass; a
# failing restore fails the script (pipefail through tee, not tee's status).
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/restore-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/dotnet" <<'STUB'
#!/usr/bin/env bash
# STUB_MODE: ok | nu1602-monomod | nu1603-interop | nu1602-cpp2il | restore-fail
echo "$*" >> "$STUB_ARGV_LOG"
case "${1:-}" in
    restore)
        case "${STUB_MODE:-ok}" in
            nu1602-monomod) echo "warning NU1602: MonoMod.Core 25.3.7-android.1 was not found, resolved MonoMod.Core 25.0.12 instead" ;;
            nu1603-interop) echo "warning NU1603: Il2CppInterop.Runtime 1.5.3-android.1 is a prerelease, resolved Il2CppInterop.Runtime 1.5.2 instead" ;;
            nu1602-cpp2il) echo "warning NU1602: Samboy063.Cpp2IL.Core 2022.1.0-development.1452 was not found" ;;
            restore-fail) echo "restore exploded"; exit 3 ;;
        esac
        echo "restore ok" ;;
    build)
        echo "build ok" ;;
    *)
        echo "unexpected dotnet argv: $*" >&2; exit 9 ;;
esac
STUB
chmod +x "$WORK/dotnet"

export PATH="$WORK:$PATH"
export STUB_ARGV_LOG="$WORK/argv.log"
export BEPINEX_SRC="$WORK/empty-tree"
mkdir -p "$BEPINEX_SRC"

fails=0
run_case() { # run_case <name> <stub-mode> <want-exit>
    local name="$1" mode="$2" want="$3"
    export STUB_MODE="$mode"
    : > "$STUB_ARGV_LOG"
    if ./scripts/restore_and_build.sh >/dev/null 2>&1; then rc=0; else rc=$?; fi
    if [ "$rc" -eq "$want" ]; then
        echo "ok    $name (exit $rc)"
    else
        echo "FAIL  $name: expected exit $want, got $rc"
        fails=$((fails + 1))
    fi
}

echo "== restore_and_build.sh =="
run_case "clean tree passes" ok 0
run_case "NU1602 on our MonoMod fails" nu1602-monomod 1
run_case "NU1603 on our Il2CppInterop fails" nu1603-interop 1
run_case "NU1602 on upstream Cpp2IL still passes" nu1602-cpp2il 0
run_case "failing restore fails the script" restore-fail 3

# Order and interface: restore first, then build with the pinned flags.
export STUB_MODE=ok
: > "$STUB_ARGV_LOG"
./scripts/restore_and_build.sh >/dev/null 2>&1
restore_at="$(grep -n "^restore " "$STUB_ARGV_LOG" | head -1 | cut -d: -f1)"
build_at="$(grep -n "^build " "$STUB_ARGV_LOG" | head -1 | cut -d: -f1)"
if [ -n "$restore_at" ] && [ -n "$build_at" ] && [ "$restore_at" -lt "$build_at" ]; then
    echo "ok    restore runs before build"
else
    echo "FAIL  restore/build order or invocation missing"
    fails=$((fails + 1))
fi
if grep -q "GeneratePackageOnBuild=false" "$STUB_ARGV_LOG" && grep -q -- "--no-restore" "$STUB_ARGV_LOG"; then
    echo "ok    build carries the pinned flags"
else
    echo "FAIL  build flags changed"
    fails=$((fails + 1))
fi
if grep -q "SourceRevisionId=" "$STUB_ARGV_LOG"; then
    echo "ok    build stamps the pin, not the scratch HEAD"
else
    echo "FAIL  SourceRevisionId flag missing"
    fails=$((fails + 1))
fi
if grep -q "RestoreLockedMode=true" "$STUB_ARGV_LOG"; then
    echo "ok    restore is locked"
else
    echo "FAIL  locked restore flag missing"
    fails=$((fails + 1))
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
