#!/usr/bin/env bash
# Fixture: scripts/emit_provenance.sh writes the as-built provenance record.
#
# WHY THIS EXISTS
#
# The pins record intended inputs; the gates enforce that the build used
# them. This script records the as-built side next to the artifacts, so a
# consumer does not have to re-run the build to learn what went into them.
# It is informational by design: a missing tool degrades its field to
# "unknown" instead of failing the build, because enforcement lives in the
# gates. The fixture proves the record is well-formed, names the real
# inputs, and degrades gracefully.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/emit-provenance.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

SCRIPT="$REPO/scripts/emit_provenance.sh"
fails=0

# A stub dotnet so the test does not depend on the real toolchain version.
mkdir -p "$WORK/bin"
printf '#!/bin/sh\necho 9.9.999-stub\n' > "$WORK/bin/dotnet"
chmod +x "$WORK/bin/dotnet"

echo "== emit_provenance.sh =="

# 1. Well-formed record naming the real inputs.
cat > "$WORK/validate.py" << 'PYEOF'
import json, os, subprocess, sys
from pathlib import Path
repo = Path(os.environ["CHECK_REPO"])
rec = json.loads(Path(sys.argv[1]).read_text())
want_keys = {"schema", "generated_utc", "repo_commit", "bepinex_upstream",
             "patched_tree", "patch_queue", "dotnet_sdk", "ci"}
missing = want_keys - set(rec)
assert not missing, f"missing keys: {missing}"
assert rec["repo_commit"] == subprocess.run(
    ["git", "-C", str(repo), "rev-parse", "HEAD"],
    capture_output=True, text=True).stdout.strip(), "repo_commit wrong"
assert rec["patched_tree"] == (repo / "references" / "patched-tree.sha256").read_text().strip(), \
    "patched_tree does not match the recorded hash"
patches = sorted((repo / "patches").glob("*.patch"))
assert len(rec["patch_queue"]) == len(patches) and patches, "patch_queue incomplete"
for entry, p in zip(rec["patch_queue"], patches):
    assert entry["file"] == p.name, f"patch order wrong: {entry['file']}"
    digest = subprocess.run(["sha256sum", str(p)], capture_output=True, text=True).stdout.split()[0]
    assert entry["sha256"] == digest, f"hash wrong for {p.name}"
assert rec["dotnet_sdk"]["actual"] == "9.9.999-stub", "stub dotnet version not recorded"
assert rec["dotnet_sdk"]["pinned"] == json.loads((repo / "global.json").read_text())["sdk"]["version"], \
    "pinned SDK not from global.json"
PYEOF
if ! PATH="$WORK/bin:$PATH" "$SCRIPT" "$WORK/prov.json" >/dev/null; then
    echo "FAIL  emit failed on the real tree"
    fails=$((fails + 1))
elif CHECK_REPO="$REPO" python3 "$WORK/validate.py" "$WORK/prov.json"; then
    echo "ok    well-formed record naming the real inputs (exit 0)"
else
    echo "FAIL  record failed validation"
    fails=$((fails + 1))
fi

# 2. A missing dotnet degrades to "unknown" instead of failing the build.
if PATH="/usr/bin:/bin" "$SCRIPT" "$WORK/prov2.json" >/dev/null 2>&1; then
    actual="$(python3 -c "import json; print(json.load(open('$WORK/prov2.json'))['dotnet_sdk']['actual'])")"
    if [ "$actual" = "unknown" ]; then
        echo "ok    missing dotnet degrades to unknown (exit 0)"
    else
        echo "FAIL  missing dotnet recorded as '$actual', want 'unknown'"
        fails=$((fails + 1))
    fi
else
    echo "FAIL  emit failed without dotnet on PATH"
    fails=$((fails + 1))
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
