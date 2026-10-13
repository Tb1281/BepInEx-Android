#!/usr/bin/env bash
# Fixture: scripts/verify_locked_versions.sh compares restored versions
# against vendored lock files and fails loud on drift.
#
# WHY THIS EXISTS
#
# NuGet's RestoreLockedMode cannot be used for the BepInEx solution: it
# validates contentHash, and the locally-built MonoMod/Il2CppInterop packages
# are rebuilt on every run, so their hashes never match a vendored file
# (NU1403 on every restore). This script is the replacement gate: it checks
# the versions the lock files were introduced for, ignoring the hashes that
# cannot be stable. A gate that silently passes on drift is worse than no
# gate, so the fixture is mostly about the failure modes.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/verify-versions.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

SCRIPT="$REPO/scripts/verify_locked_versions.sh"

# Layout: $WORK/locks/<proj>/packages.lock.json mirrors $WORK/src/<proj>/,
# which holds obj/project.assets.json after a restore.
make_lock() { # make_lock <dir> <proj> <pkg> <ver>
    local dir="$1" proj="$2" pkg="$3" ver="$4"
    mkdir -p "$dir/$proj"
    cat > "$dir/$proj/packages.lock.json" <<EOF
{
  "version": 1,
  "dependencies": {
    ".NETCoreApp,Version=v10.0": {
      "$pkg": {
        "type": "Direct",
        "requested": "[$ver, )",
        "resolved": "$ver",
        "contentHash": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
      }
    }
  }
}
EOF
}

make_assets() { # make_assets <dir> <proj> <pkg> <ver>
    local dir="$1" proj="$2" pkg="$3" ver="$4"
    mkdir -p "$dir/$proj/obj"
    cat > "$dir/$proj/obj/project.assets.json" <<EOF
{
  "version": 3,
  "targets": {
    ".NETCoreApp,Version=v10.0": {
      "$pkg/$ver": { "type": "package" }
    }
  }
}
EOF
}

fails=0
run_case() { # run_case <name> <want-exit> [setup...]
    local name="$1" want="$2"
    if "$SCRIPT" "$WORK/src" "$WORK/locks" >/dev/null 2>&1; then rc=0; else rc=$?; fi
    if [ "$rc" -eq "$want" ]; then
        echo "ok    $name (exit $rc)"
    else
        echo "FAIL  $name: expected exit $want, got $rc"
        fails=$((fails + 1))
    fi
    rm -rf "$WORK/src" "$WORK/locks"
    mkdir -p "$WORK/src" "$WORK/locks"
}

echo "== verify_locked_versions.sh =="
mkdir -p "$WORK/src" "$WORK/locks"

# 1. Matching versions pass.
make_lock "$WORK/locks" ProjA Fake.Package 1.2.3
make_assets "$WORK/src" ProjA Fake.Package 1.2.3
run_case "matching versions pass" 0

# 2. Version drift fails.
make_lock "$WORK/locks" ProjA Fake.Package 1.2.3
make_assets "$WORK/src" ProjA Fake.Package 2.0.0
run_case "version drift fails" 1

# 3. Missing restore output fails (fail closed, not silent pass).
make_lock "$WORK/locks" ProjA Fake.Package 1.2.3
mkdir -p "$WORK/src/ProjA"  # no obj/project.assets.json
run_case "missing project.assets.json fails" 1

# 4. A package absent from the restore fails.
make_lock "$WORK/locks" ProjA Fake.Package 1.2.3
make_assets "$WORK/src" ProjA Other.Package 1.2.3
run_case "package missing from restore fails" 1

# 5. Empty lock dir fails (a gate with nothing to check must not pass).
run_case "empty lock dir fails" 1

# 6. Multiple projects, one drifting, names the drifter.
make_lock "$WORK/locks" ProjA Fake.Package 1.2.3
make_assets "$WORK/src" ProjA Fake.Package 1.2.3
make_lock "$WORK/locks" ProjB Other.Package 4.5.6
make_assets "$WORK/src" ProjB Other.Package 4.5.7
out="$("$SCRIPT" "$WORK/src" "$WORK/locks" 2>&1 || true)"
if echo "$out" | grep -q "ProjB.*Other.Package.*4.5.6"; then
    echo "ok    drift names the project and package (exit 1)"
else
    echo "FAIL  drift report does not name ProjB/Other.Package"
    echo "$out"
    fails=$((fails + 1))
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
