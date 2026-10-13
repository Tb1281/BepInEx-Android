#!/usr/bin/env bash
# Verify the patched BepInEx tree is exactly the recorded one.
#
# WHY THIS EXISTS
#
# The patch queue is replayed with `git am`, which mints commits whose
# committer dates are "now" on every run - so the tip commit SHA can never be
# compared across runs. But the tree object that tip points at is a pure
# content hash of the patched sources (modes, names, blob hashes; no
# timestamps), and `git am` is a deterministic text operation: the same pin
# plus the same ordered queue always yields the same tree, on any machine.
#
# That makes the tree hash the stable identity of "the port". An output hash
# cannot serve this role - the same patched sources compiled at different
# absolute paths produced DLLs differing by 16,923 bytes, and `dotnet pack`
# embeds timestamps in every nupkg (see docs/operations.md) - so the patch is
# verified here, at the source layer, while the build is verified by
# properties (package versions, SourceRevisionId, 16KB page alignment) at the
# artifact layer. Verifying the patch at the artifact layer is the NU1403
# trap: it fails on every run for reasons that have nothing to do with the
# patch.
#
# What this refuses:
#   - a queue that replayed differently than recorded (a changed patch, a
#     reordered queue, a hunk that --3way resolved differently),
#   - a silently skipped patch (apply_patches.sh already fails a patch that
#     does not apply, but nothing stops a queue edit that drops one),
#   - a tree not built on the pinned upstream commit,
#   - untracked files in the scratch tree, which the tree hash cannot see.
#
# Usage:
#   verify_patches.sh <source-dir> <pin-commit> <expected-hash-file>
#
# Exit 0 when the tree hash matches the recorded one, 1 otherwise.
set -euo pipefail

SRC_DIR="${1:?usage: verify_patches.sh <source-dir> <pin-commit> <expected-hash-file>}"
PIN="${2:?usage: verify_patches.sh <source-dir> <pin-commit> <expected-hash-file>}"
EXPECTED_FILE="${3:?usage: verify_patches.sh <source-dir> <pin-commit> <expected-hash-file>}"

[ -d "$SRC_DIR/.git" ] || { echo "not a git repository: $SRC_DIR" >&2; exit 1; }
[ -f "$EXPECTED_FILE" ] || { echo "missing expected tree-hash file: $EXPECTED_FILE" >&2; exit 1; }
git -C "$SRC_DIR" rev-parse --verify HEAD >/dev/null 2>&1 \
    || { echo "no HEAD in $SRC_DIR; run apply_patches.sh first" >&2; exit 1; }

# The tree hash alone does not say what base it was built on. Without this, a
# tree replayed onto a different upstream - or a recorded hash belonging to
# another pin - would pass on content alone.
git -C "$SRC_DIR" merge-base --is-ancestor "$PIN" HEAD \
    || { echo "HEAD in $SRC_DIR is not built on the pinned commit $PIN" >&2; exit 1; }

# Untracked files are invisible to the tree hash. After a fresh clone and
# `git am` there are none; anything here is residue or tampering, and either
# way the hash no longer describes the whole tree.
untracked="$(git -C "$SRC_DIR" status --porcelain | grep '^??' || true)"
if [ -n "$untracked" ]; then
    echo "untracked files in the patched tree (not covered by the tree hash):" >&2
    echo "$untracked" >&2
    exit 1
fi

TREE="$(git -C "$SRC_DIR" rev-parse "HEAD^{tree}")"
EXPECTED="$(tr -d '[:space:]' < "$EXPECTED_FILE")"
[ -n "$EXPECTED" ] || { echo "expected tree-hash file is empty: $EXPECTED_FILE" >&2; exit 1; }

echo "patched-tree: $TREE"
if [ "$TREE" != "$EXPECTED" ]; then
    echo "[FAIL] patched tree $TREE does not match recorded $EXPECTED" >&2
    echo "diff against the pin, for orientation:" >&2
    git -C "$SRC_DIR" diff --stat "$PIN" | tail -8 >&2 || true
    echo "If the queue changed on purpose, re-record with scripts/record_patched_tree.sh" >&2
    exit 1
fi
echo "[OK] patched tree matches the recorded hash"
