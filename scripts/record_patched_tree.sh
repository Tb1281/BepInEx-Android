#!/usr/bin/env bash
# Record the patched-tree hash after an intentional queue change.
#
# Run ./scripts/apply_patches.sh first, then this. It writes the tree-object
# hash of the fresh scratch tree to references/patched-tree.sha256. Commit
# the updated file alongside the patch change: the hash is a lock file for
# the queue, and like any lock file it is only ever updated deliberately,
# never by a build.
#
# What this refuses:
#   - an open `git am` session: the queue stopped at a conflict, so the tree
#     is a prefix, not the queue's result;
#   - a tree that is not clean (staged, unstaged, or untracked): the tree hash
#     does not describe working-tree residue;
#   - a tree not built on the pinned commit;
#   - a replay whose commit count does not match the current queue glob: a
#     narrowed or partially replayed queue must not become the recorded truth.
#
# The count is the second half of the tree gate. verify_patches.sh checks the
# content of a complete replay; this checks that the replay it is about to
# record was complete in the first place.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

PIN_FILE="$BEPINEX_PIN_FILE"
[ -f "$PIN_FILE" ] || { echo "missing pin file: $PIN_FILE" >&2; exit 1; }
# Same sourcing as apply_patches.sh: the pin file carries more than the pin.
PIN="$( ( set -a; . "$PIN_FILE"; set +a; printf '%s' "${UPSTREAM_COMMIT:?UPSTREAM_COMMIT missing from $PIN_FILE}" ) )"

[ -d "$BEPINEX_SRC/.git" ] || { echo "no patched tree at $BEPINEX_SRC; run apply_patches.sh first" >&2; exit 1; }

# `git am` leaves one of these directories behind while the queue is stopped.
# Checking it first gives the reader the actual reason (the queue is open),
# rather than the count check's symptom (a prefix was applied).
for state in rebase-apply rebase-merge; do
    state_path="$(git -C "$BEPINEX_SRC" rev-parse --git-path "$state")"
    case "$state_path" in
        /*) ;;
        *) state_path="$BEPINEX_SRC/$state_path" ;;
    esac
    if [ -d "$state_path" ]; then
        echo "refusing to record: git am is still in progress in $BEPINEX_SRC" >&2
        echo "the queue is partial; resolve it or run 'git am --abort' before recording" >&2
        exit 1
    fi
done

# Any working-tree state means the tree hash no longer describes what is on
# disk. The old check only looked at untracked files, so a conflicted or
# partially staged tree could still be recorded.
status="$(git -C "$BEPINEX_SRC" status --porcelain)"
if [ -n "$status" ]; then
    echo "refusing to record: patched tree is not clean:" >&2
    printf '%s\n' "$status" >&2
    exit 1
fi

git -C "$BEPINEX_SRC" merge-base --is-ancestor "$PIN" HEAD \
    || { echo "refusing to record: HEAD is not built on the pinned commit $PIN" >&2; exit 1; }

# The queue glob is the authority for how many commits a complete replay has.
# A prefix (one patch of two) is a valid git history but not the queue's
# result, and recording it would make the next full replay fail the gate for
# the wrong reason - or, worse, make a partial port look verified.
shopt -s nullglob
patches=($BEPINEX_PATCH_GLOB)
expected=${#patches[@]}
[ "$expected" -gt 0 ] || { echo "refusing to record: no patches match $BEPINEX_PATCH_GLOB" >&2; exit 1; }
applied="$(git -C "$BEPINEX_SRC" rev-list --count "$PIN..HEAD")"
if [ "$applied" -ne "$expected" ]; then
    echo "refusing to record: partial replay ($applied of $expected patches applied)" >&2
    echo "the tree at $BEPINEX_SRC is a prefix of the queue, not the queue's result" >&2
    exit 1
fi

TREE="$(git -C "$BEPINEX_SRC" rev-parse "HEAD^{tree}")"
OUT="$BEPINEX_PATCHED_TREE_FILE"
mkdir -p "$(dirname "$OUT")"
printf '%s\n' "$TREE" > "$OUT"
echo "recorded patched-tree $TREE -> ${OUT#"$ROOT"/}"
