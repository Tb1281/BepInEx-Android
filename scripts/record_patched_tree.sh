#!/usr/bin/env bash
# Record the patched-tree hash after an intentional queue change.
#
# Run ./scripts/apply_patches.sh first, then this. It writes the tree-object
# hash of the fresh scratch tree to references/patched-tree.sha256. Commit
# the updated file alongside the patch change: the hash is a lock file for
# the queue, and like any lock file it is only ever updated deliberately,
# never by a build.
#
# Refuses to record over a tree with untracked files, because the tree hash
# cannot see them and the recorded value would then describe less than what
# was actually there.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

PIN_FILE="$BEPINEX_PIN_FILE"
[ -f "$PIN_FILE" ] || { echo "missing pin file: $PIN_FILE" >&2; exit 1; }
# Same sourcing as apply_patches.sh: the pin file carries more than the pin.
PIN="$( ( set -a; . "$PIN_FILE"; set +a; printf '%s' "${UPSTREAM_COMMIT:?UPSTREAM_COMMIT missing from $PIN_FILE}" ) )"

[ -d "$BEPINEX_SRC/.git" ] || { echo "no patched tree at $BEPINEX_SRC; run apply_patches.sh first" >&2; exit 1; }

untracked="$(git -C "$BEPINEX_SRC" status --porcelain | grep '^??' || true)"
if [ -n "$untracked" ]; then
    echo "refusing to record: untracked files in $BEPINEX_SRC:" >&2
    echo "$untracked" >&2
    exit 1
fi

TREE="$(git -C "$BEPINEX_SRC" rev-parse "HEAD^{tree}")"
OUT="$ROOT/references/patched-tree.sha256"
printf '%s\n' "$TREE" > "$OUT"
echo "recorded patched-tree $TREE -> ${OUT#"$ROOT"/}"
