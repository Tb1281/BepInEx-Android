#!/usr/bin/env bash
# Emit an as-built provenance record for this run as JSON.
#
# WHY THIS EXISTS
#
# The pins record *intended* inputs: provenance/*.env names the upstream
# commits, references/patched-tree.sha256 names the expected replay, and the
# gates (pin check, tree-hash gate, version gate) enforce that the build used
# them. What no artifact recorded is the *as-built* side: the tree hash the
# replay actually produced, the SDK version that actually compiled, the exact
# patch files that were replayed. The gates make intended==actual hold, but a
# consumer holding the artifacts should not have to re-run the build to learn
# what went into them.
#
# This writes that record next to the artifacts (build-bepinex.yml attests it
# alongside BepInEx.Android.zip; sync-upstream.yml uploads it in the CI
# artifact). It is informational: a missing tool degrades a field to "unknown"
# instead of failing the build. The enforcement stays in the gates.
#
# Usage:
#   emit_provenance.sh [output-file]   (default: stdout)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-/dev/stdout}"

export ROOT OUT
python3 << 'PYEOF'
import datetime, hashlib, json, os, subprocess
from pathlib import Path

root = Path(os.environ["ROOT"])

def read_env_file(path):
    vals = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip()
    return vals

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def run_quiet(*args):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=30).stdout.strip()
    except Exception:
        return ""

bepinex_env = read_env_file(root / "provenance" / "bepinex.env")
tree_file = root / "references" / "patched-tree.sha256"
patched_tree = tree_file.read_text().strip() if tree_file.is_file() else "unknown"

patch_queue = []
for p in sorted((root / "patches").glob("*.patch")):
    patch_queue.append({"file": p.name, "sha256": sha256_of(p)})

sdk_pinned = "unknown"
gpath = root / "global.json"
if gpath.is_file():
    try:
        sdk_pinned = json.loads(gpath.read_text())["sdk"]["version"]
    except Exception:
        pass
sdk_actual = run_quiet("dotnet", "--version") or "unknown"

def ci(name):
    v = os.environ.get(name, "")
    return v if v else None

record = {
    "schema": "bepinex-android/provenance/1",
    "generated_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "repo_commit": run_quiet("git", "-C", str(root), "rev-parse", "HEAD") or "unknown",
    "bepinex_upstream": {
        "url": bepinex_env.get("UPSTREAM_URL", "unknown"),
        "commit": bepinex_env.get("UPSTREAM_COMMIT", "unknown"),
    },
    "patched_tree": patched_tree,
    "patch_queue": patch_queue,
    "dotnet_sdk": {"pinned": sdk_pinned, "actual": sdk_actual},
    "ci": {
        "workflow": ci("GITHUB_WORKFLOW"),
        "run_id": ci("GITHUB_RUN_ID"),
        "sha": ci("GITHUB_SHA"),
        "actor": ci("GITHUB_ACTOR"),
    },
}

out = os.environ["OUT"]
with open(out, "w", encoding="utf-8") as fp:
    json.dump(record, fp, indent=2)
    fp.write("\n")
print(f"provenance -> {out} ({len(patch_queue)} patches, tree {patched_tree[:12]})")
PYEOF
