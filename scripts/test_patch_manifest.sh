#!/usr/bin/env bash
# Fixture: patches/manifest.json is the generated, drift-tested interface of
# the patch queue.
#
# WHY THIS EXISTS
#
# The queue was readable only by replaying it. The manifest makes purpose,
# touched files, dependencies and compile-unit status machine-readable, and
# the drift test makes sure it never silently disagrees with the patches it
# describes. A manifest that says 13 patches while 12 exist, or lists a
# dependency that was renamed away, is worse than no manifest.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/patch-manifest.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fails=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; shift; [ "$#" -gt 0 ] && printf '%s\n' "$@"; fails=$((fails + 1)); }

GENERATOR="$REPO/scripts/generate_patch_manifest.py"
MANIFEST="$REPO/patches/manifest.json"

echo "== generate_patch_manifest.py: drift =="

if "$GENERATOR" --output "$WORK/generated.json" >/dev/null 2>&1; then
    pass "generator runs"
else
    fail "generator runs"
fi

if diff -u "$MANIFEST" "$WORK/generated.json" >/dev/null 2>&1; then
    pass "committed manifest matches the generator"
else
    fail "committed manifest matches the generator" \
        "$(diff -u "$MANIFEST" "$WORK/generated.json" | sed -n '1,80p')"
fi

echo "== patch manifest: validation =="

cat > "$WORK/validator.py" <<'PY'
import glob
import json
import os
import re
import sys

repo = sys.argv[1]
manifest_path = sys.argv[2]
patches_dir = os.path.join(repo, "patches")

with open(manifest_path, encoding="utf-8") as f:
    manifest = json.load(f)
assert isinstance(manifest, list), "manifest must be a JSON array"

patch_paths = sorted(glob.glob(os.path.join(patches_dir, "*.patch")))
patch_ids = [os.path.splitext(os.path.basename(p))[0] for p in patch_paths]
assert len(manifest) == len(patch_paths), (
    f"manifest has {len(manifest)} entries but {len(patch_paths)} patches exist"
)

ids = [e["id"] for e in manifest]
assert len(ids) == len(set(ids)), "duplicate manifest ids"
assert set(ids) == set(patch_ids), "manifest ids do not match the patch filenames"

id_set = set(ids)
for e in manifest:
    for dep in e.get("depends_on", []):
        assert dep in id_set, f"{e['id']} depends on unknown {dep}"

colour = {}
def visit(node):
    if node in colour:
        return colour[node] == 1
    colour[node] = 0
    entry = next(e for e in manifest if e["id"] == node)
    for dep in entry.get("depends_on", []):
        if not visit(dep):
            return False
    colour[node] = 1
    return True
for e in manifest:
    assert visit(e["id"]), f"dependency cycle involving {e['id']}"

subjects = [e["subject"] for e in manifest]
assert len(subjects) == len(set(subjects)), "duplicate subjects"

COMPILE_EXT = {".cs", ".cpp", ".cc", ".c", ".h", ".hpp", ".hh",
               ".csproj", ".props", ".targets", ".sln"}


def patch_files(path):
    out = []
    seen = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^diff --git a/(.+?) b/(.+)$", line)
            if m:
                p = m.group(2)
                if p == "/dev/null":
                    p = m.group(1)
                if p not in seen:
                    seen.add(p)
                    out.append(p)
    return sorted(out)


def patch_subject(path):
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    subj = []
    in_subj = False
    for line in lines:
        if line.startswith("Subject:"):
            in_subj = True
            subj.append(line[len("Subject:"):].strip())
            continue
        if in_subj:
            if line.strip() == "" or line.strip() == "---" or line.startswith("diff --git "):
                break
            if line.startswith(" ") or line.startswith("\t"):
                subj.append(line.strip())
                continue
            break
    s = " ".join(subj).strip()
    s = re.sub(r"^\[PATCH(?: \d+/\d+)?\]\s*", "", s)
    return s


for e in manifest:
    path = os.path.join(patches_dir, e["id"] + ".patch")
    assert os.path.isfile(path), f"missing patch file for {e['id']}"
    actual_files = patch_files(path)
    assert e["files"] == actual_files, (
        f"{e['id']}: files mismatch: manifest={e['files']} patch={actual_files}"
    )
    assert e["subject"] == patch_subject(path), (
        f"{e['id']}: subject mismatch: manifest={e['subject']!r} patch={patch_subject(path)!r}"
    )
    expected_cu = any(os.path.splitext(f)[1].lower() in COMPILE_EXT for f in actual_files)
    assert e["compile_unit"] == expected_cu, (
        f"{e['id']}: compile_unit should be {expected_cu}"
    )
    assert e["number"] == int(e["id"].split("-", 1)[0]), f"{e['id']}: bad number"
    assert isinstance(e["depends_on"], list)
    assert isinstance(e["adr"], list)
    for a in e["adr"]:
        assert re.fullmatch(r"\d{4}", a), f"{e['id']}: bad ADR id {a}"

print("manifest validates:", len(manifest), "entries")
PY

if python3 "$WORK/validator.py" "$REPO" "$MANIFEST" >"$WORK/validation.out" 2>&1; then
    pass "manifest validates"
    cat "$WORK/validation.out"
else
    fail "manifest validates" "$(cat "$WORK/validation.out")"
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"