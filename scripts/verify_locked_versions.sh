#!/usr/bin/env bash
# Verify that restored package versions match the vendored lock files.
#
# The lock files in references/nuget-locks record the exact versions every
# restore must resolve. NuGet's own locked mode (RestoreLockedMode) cannot be
# used here: it also validates contentHash, and the locally-built
# MonoMod/Il2CppInterop packages are rebuilt from source on every run, so
# their hashes can never match a vendored file (dotnet pack embeds timestamps;
# see scripts/restore_and_build.sh). That failed every restore with NU1403.
#
# This script checks what the lock files are actually for: versions. A version
# drift - the "transitives floated per restore" failure - is loud here instead
# of silent. Hash validation for the rebuilt packages is intentionally out of
# scope; they are built from pinned source in the same run.
#
# Usage:
#   verify_locked_versions.sh <source-dir> <lock-dir>
#
#   <source-dir>  restored tree; expects <project>/obj/project.assets.json
#   <lock-dir>    vendored lock files, mirroring the tree layout, each a
#                 packages.lock.json next to its project
#
# Exit 0 if every locked version matches the restore, 1 otherwise.
set -euo pipefail

SRC_DIR="${1:?usage: verify_locked_versions.sh <source-dir> <lock-dir>}"
LOCK_DIR="${2:?usage: verify_locked_versions.sh <source-dir> <lock-dir>}"

[ -d "$SRC_DIR" ] || { echo "source dir not found: $SRC_DIR" >&2; exit 1; }
[ -d "$LOCK_DIR" ] || { echo "lock dir not found: $LOCK_DIR" >&2; exit 1; }

export SRC_DIR LOCK_DIR
python3 << 'PYEOF'
import json, os, sys
from pathlib import Path

src = Path(os.environ['SRC_DIR'])
lock_dir = Path(os.environ['LOCK_DIR'])

failures = []
checked = 0

for lock_file in sorted(lock_dir.rglob('packages.lock.json')):
    rel = lock_file.relative_to(lock_dir)
    proj_dir = src / rel.parent
    assets = proj_dir / 'obj' / 'project.assets.json'

    if not assets.is_file():
        failures.append(f"[MISSING] {rel}: no obj/project.assets.json (restore first)")
        continue

    locked = json.loads(lock_file.read_text())
    restored = json.loads(assets.read_text())

    # All libraries the restore resolved, as (id, version) pairs.
    restored_libs = set()
    for target, libs in restored.get('targets', {}).items():
        for lib in libs:
            # Library keys are 'id/version'; framework assemblies have no '/'.
            if '/' in lib:
                pkg_id, ver = lib.rsplit('/', 1)
                restored_libs.add((pkg_id.lower(), ver))

    seen = set()
    for fw, deps in locked.get('dependencies', {}).items():
        for pkg_id, info in deps.items():
            ver = info.get('resolved', '')
            key = (pkg_id.lower(), ver)
            if not ver or key in seen:
                continue
            seen.add(key)
            checked += 1
            if key not in restored_libs:
                # Name the actual resolved version, if any, to explain the drift.
                actual = sorted(v for (i, v) in restored_libs if i == pkg_id.lower())
                failures.append(
                    f"[DRIFT] {rel}: {pkg_id} locked at {ver}, "
                    f"restore resolved {actual if actual else 'nothing'}"
                )

print(f"checked {checked} locked package versions")
if checked == 0:
    print("[FAIL] no packages found in lock files; a gate with nothing to check must not pass",
          file=sys.stderr)
    sys.exit(1)
if failures:
    print(f"[FAIL] {len(failures)} version drift(s) detected:", file=sys.stderr)
    for f in failures:
        print(f"  {f}", file=sys.stderr)
    sys.exit(1)
print("[OK] all restored versions match the lock files")
PYEOF
