#!/usr/bin/env bash
# Describe why a patch in the queue could not be replayed onto the pinned
# upstream commit.
#
# `git am --3way` fails, it says only that a patch failed. Resolving it needs
# three things it does not print:
#
#   1. which patch stopped the queue, and at which patch in the sequence;
#   2. which file and line, and what each side wanted there - the conflict
#      markers already hold this, but they are buried in a worktree nobody
#      looks at on a CI runner;
#   3. what upstream did to that code, i.e. which upstream commits are in the
#      pinned tree but not in the tree the patch was generated against.
#
# This prints all three as text and, if GITHUB_STEP_SUMMARY is set, writes the
# same thing to the job summary so a scheduled run's failure is readable
# without opening the log. It never edits anything: resolving is a human (or a
# later regeneration of the patch), not something this script should decide.
#
# Usage:
#   scripts/report_patch_conflict.sh <failed-patch-path> <applied-count>
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"
SUB="$BEPINEX_SRC"

# The conflict markers this script reads live in the clone apply_patches.sh was
# replaying into, not in a submodule. If that tree is gone there is nothing to
# diagnose - and reporting "upstream changed" from an empty directory would send
# the reader to a file that never existed.
if [ ! -d "$SUB/.git" ]; then
    echo "no patched source tree at ${SUB#"$ROOT"/}; nothing to diagnose." >&2
    echo "apply_patches.sh removes and recreates it per run, so a missing tree" >&2
    echo "means the failure happened before or outside the replay." >&2
    exit 0
fi
cd "$SUB"

FAILED_PATCH="${1:-}"
APPLIED="${2:-0}"

# Callers pass a path relative to the repo root and this script cd's into the
# submodule, so resolve it before moving.
case "$FAILED_PATCH" in
    /*) ;;
    *) FAILED_PATCH="$ROOT/$FAILED_PATCH" ;;
esac

shopt -s nullglob
ALL_PATCHES=("$ROOT"/patches/*.patch)
[ -n "$FAILED_PATCH" ] || FAILED_PATCH="${ALL_PATCHES[0]:-<unknown>}"

name="$(basename "$FAILED_PATCH")"
index=0
for p in "${ALL_PATCHES[@]}"; do
    index=$((index + 1))
    [ "$(basename "$p")" = "$name" ] && break
done

# The pin is now a recorded value in provenance/bepinex.env, not a gitlink that
# has to be resolved out of the superproject index. Read it from there: the
# fallback is the clone's own HEAD, which is the commit the replay actually ran
# against and therefore the honest thing to name if the pin file is unreadable.
PIN="$(sed -n 's/^UPSTREAM_COMMIT=//p' "$BEPINEX_PIN_FILE" | head -1)"
if [ -z "$PIN" ]; then
    PIN="$(git rev-parse HEAD 2>/dev/null || echo '<unknown>')"
fi
PIN_SUBJECT="$(git log -1 --format='%s' "$PIN" 2>/dev/null || echo '<unknown>')"

out() { printf '%s\n' "$*"; printf '%s\n' "$*" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"; }

rule() { out "------------------------------------------------------------------"; }

out ""
rule
out "Android patch queue could not be replayed"
rule
out "upstream pin : $PIN"
out "              $PIN_SUBJECT"
out "failed patch : $name  ($index of ${#ALL_PATCHES[@]}, after $APPLIED applied)"
out ""

conflicted="$(git diff --name-only --diff-filter=U 2>/dev/null)"

# Distinguish the three ways the queue can stop, because only one is a conflict.
# A runner with no committer identity makes git am fail before it reads a file,
# and calling that "upstream changed" sends the reader to the wrong place.
if [ -z "$(git config user.name || true)" ] || [ -z "$(git config user.email || true)" ]; then
    out "No committer identity is configured, so git am could not mint a commit"
    out "for the patch. That is not a conflict: no file was read or compared."
    out "Configure user.name/user.email, or let scripts/apply_patches.sh set them."
    out ""
elif [ -z "$conflicted" ]; then
    out "The patch did not apply and was rolled back instead of being left"
    out "conflicted, which usually means its target file or hunk is gone upstream"
    out "rather than merely edited."
    out ""
else
    out "The patch applies to a tree upstream has since changed. The queue is the"
    out "only description of the Android port, so this blocks every build until"
    out "the patch is regenerated. Nothing here has been auto-resolved."
    out ""
fi

# 1. Which upstream commits moved since the patch was generated.
#
# The patch's own index line records the blob it was made against, so the base
# can be identified even when the commit is not an ancestor of anything we hold.
base_blob="$(sed -n 's|^index \([0-9a-f]*\)\.\..*|\1|p' "$FAILED_PATCH" | head -1)"
out "Upstream commits in the pin that are not in this repository's history:"
unmerged="$(git log --oneline -20 "$PIN" --not --remotes 2>/dev/null)"
if [ -n "$unmerged" ]; then
    printf '%s\n' "$unmerged" | sed 's/^/  /'
else
    out "  (none: this pin's history is fully contained in origin/master)"
fi
out ""
[ -n "$base_blob" ] && out "patch base blob: $base_blob"
out ""

# 2. The conflicting files, with the conflict itself.
if [ -n "$conflicted" ]; then
    out "Conflicted files:"
    printf '%s\n' "$conflicted" | sed 's/^/  /'
    out ""
    for f in $conflicted; do
        rule
        out "$f"
        rule
        # The markers are the whole diagnosis: HEAD is upstream's line, the other
        # side is what the port wanted in its place.
        awk '/^<<<<<<</{p=1} p{print} /^>>>>>>>/{p=0}' "$f" 2>/dev/null | sed 's/^/  /'
    done
fi

# 3. What upstream did to those files, which is usually the actual explanation.
for f in $conflicted; do
    rule
    out "upstream changes to $f since the patch base"
    rule
    # --3way restores the pre-image under MERGE_RR / the rebase state, so the
    # diff is against the blob the patch was written for when available.
    # The pre-image is what the patch was written against; --3way leaves it in
    # the rebase state, and it is the diff against it that explains the clash.
    # HEAD is the pinned upstream tree, so a plain diff is not the answer: it
    # would show our own patch's changes.
    upstream_diff="$(git diff --no-color --merge-base "$PIN" "$f" 2>/dev/null)"
    if [ -n "$upstream_diff" ]; then
        printf '%s\n' "$upstream_diff" | head -60 | sed 's/^/  /'
    else
        out "  (no upstream diff available for this path)"
    fi
done

out ""
rule
out "To resolve:"
out "  1. cd ${BEPINEX_SRC#"$ROOT"/}"
out "  2. git am --show-current-patch=diff   # what the port tried to change"
out "  3. resolve the markers, then: git am --continue"
out "  4. git format-patch -1 --stdout --no-signature > patches/<name>"
out "     (re-run scripts/apply_patches.sh afterwards to confirm the queue)"
out ""
out "Do not skip the patch: the queue is what CI builds, so a skip ships a"
out "BepInEx.Android.zip missing that adaptation, and nothing downstream"
out "notices until the device behaves differently."
rule
out ""