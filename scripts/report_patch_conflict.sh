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
# The report is deliberately honest about missing evidence. A shallow clone or
# a force-pushed upstream can lack the pre-image blob, and a guessed base is
# worse than no base: it sends the reader to the wrong upstream commits. The
# base section therefore says "unknown" and keeps the parts that are known.
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
# the reader to a file that never existed. Exit 1, not 0: no tree is a failed
# diagnosis, even though apply_patches.sh ignores this status because it has
# already decided to abort.
if [ ! -d "$SUB/.git" ]; then
    echo "no patched source tree at ${SUB#"$ROOT"/}; nothing to diagnose." >&2
    echo "apply_patches.sh removes and recreates it per run, so a missing tree" >&2
    echo "means the failure happened before or outside the replay." >&2
    exit 1
fi
cd "$SUB"

FAILED_PATCH="${1:-}"
APPLIED="${2:-0}"
case "$APPLIED" in
    ''|*[!0-9]*) APPLIED=0 ;;
esac

# Callers pass a path relative to the repo root and this script cd's into the
# submodule, so resolve it before moving.
case "$FAILED_PATCH" in
    /*) ;;
    *) FAILED_PATCH="$ROOT/$FAILED_PATCH" ;;
esac

# The queue glob, not the real queue's directory. apply_patches.sh honours
# BEPINEX_PATCH_GLOB, and the report has to describe the same queue: the
# fixture, a debugging run, and a narrowed replay all pass a different glob.
# Enumerating $ROOT/patches here made the position wrong for every one of them.
shopt -s nullglob
ALL_PATCHES=($BEPINEX_PATCH_GLOB)
QUEUE_TOTAL=${#ALL_PATCHES[@]}
[ -n "$FAILED_PATCH" ] || FAILED_PATCH="${ALL_PATCHES[0]:-<unknown>}"

name="$(basename "$FAILED_PATCH")"
index=0
found=0
for p in "${ALL_PATCHES[@]}"; do
    index=$((index + 1))
    if [ "$(basename "$p")" = "$name" ]; then
        found=1
        break
    fi
done
[ "$found" = 1 ] || index=0

# The pin is now a recorded value in provenance/bepinex.env, not a gitlink that
# has to be resolved out of the superproject index. Read it from there. If the
# file is unreadable, say unknown rather than naming the last applied patch
# commit: after a conflict HEAD is the patch tip, not the pin, and calling it
# the pin would point the reader at the wrong upstream tree.
PIN=""
if [ -f "$BEPINEX_PIN_FILE" ]; then
    PIN="$(sed -n 's/^UPSTREAM_COMMIT=//p' "$BEPINEX_PIN_FILE" | head -1)"
fi
if [ -z "$PIN" ]; then
    PIN="<unknown>"
    PIN_SUBJECT="<unknown>"
else
    PIN_SUBJECT="$(git log -1 --format='%s' "$PIN" 2>/dev/null || echo '<unknown>')"
fi

out() { printf '%s\n' "$*"; printf '%s\n' "$*" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"; }

rule() { out "------------------------------------------------------------------"; }

out ""
rule
out "Android patch queue could not be replayed"
rule
out "upstream pin : $PIN"
out "              $PIN_SUBJECT"
if [ "$index" -gt 0 ]; then
    out "failed patch : $name  ($index of $QUEUE_TOTAL, after $APPLIED applied)"
else
    out "failed patch : $name  (not in the current queue; after $APPLIED applied)"
fi
out ""

mapfile -t conflicted < <(git diff --name-only --diff-filter=U 2>/dev/null || true)

# Distinguish the three ways the queue can stop, because only one is a conflict.
# A runner with no committer identity makes git am fail before it reads a file,
# and calling that "upstream changed" sends the reader to the wrong place.
if [ -z "$(git config user.name || true)" ] || [ -z "$(git config user.email || true)" ]; then
    out "No committer identity is configured, so git am could not mint a commit"
    out "for the patch. That is not a conflict: no file was read or compared."
    out "Configure user.name/user.email, or let scripts/apply_patches.sh set them."
    out ""
elif [ "${#conflicted[@]}" -eq 0 ]; then
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

# --------------------------------------------------------------------------
# Patch base. The patch's index line records the blob each file was made
# against; that is the one piece of base information that survives even when
# the base commit is not in the clone. Report it per file, because a patch can
# touch several files and each has its own pre-image.
# --------------------------------------------------------------------------
patch_files() {
    awk '
        $1 == "diff" && $2 == "--git" {
            path = $3
            sub(/^a\//, "", path)
            print path
        }
    ' "$FAILED_PATCH"
}

base_blob_for() {
    local target="$1"
    awk -v target="$target" '
        $1 == "diff" && $2 == "--git" {
            path = $3
            sub(/^a\//, "", path)
            in_file = (path == target)
            next
        }
        in_file && $1 == "index" {
            blob = $2
            sub(/\.\..*$/, "", blob)
            print blob
            exit
        }
    ' "$FAILED_PATCH"
}

# The newest commit in this clone whose tree still has the base blob for the
# file. `git log --find-object` returns both ends of a blob's lifetime; the
# newest one may be the commit that replaced it, so check the tree at each
# candidate. The first match walking backwards is the commit the patch was
# generated against.
base_commit_for() {
    local blob="$1" file="$2" commit actual
    while IFS= read -r commit; do
        [ -n "$commit" ] || continue
        actual="$(git rev-parse "$commit:$file" 2>/dev/null || true)"
        if [ "$actual" = "$blob" ]; then
            printf '%s\n' "$commit"
            return 0
        fi
    done < <(git log --all --format='%H' --find-object="$blob" -- "$file" 2>/dev/null || true)
    return 1
}

declare -A seen_base=()
base_files=()
add_base_file() {
    local file="$1"
    [ -n "$file" ] || return 0
    [ -n "${seen_base[$file]:-}" ] && return 0
    seen_base[$file]=1
    base_files+=("$file")
}
for f in "${conflicted[@]}"; do
    add_base_file "$f"
done
if [ "${#base_files[@]}" -eq 0 ]; then
    while IFS= read -r f; do
        add_base_file "$f"
    done < <(patch_files)
fi

declare -A base_commit_by_file=()
declare -A base_blob_by_file=()
if [ "${#base_files[@]}" -eq 0 ]; then
    out "patch base: unknown (the patch names no files)"
    out ""
else
    for f in "${base_files[@]}"; do
        blob="$(base_blob_for "$f")"
        if [ -z "$blob" ]; then
            out "patch base for $f: unknown (no index line in $name)"
            out ""
            continue
        fi
        full_blob="$(git rev-parse --verify "$blob^{blob}" 2>/dev/null || true)"
        if [ -z "$full_blob" ]; then
            out "patch base for $f: unknown (blob $blob is not in this clone)"
            out ""
            continue
        fi
        blob="$full_blob"
        base_blob_by_file[$f]="$blob"
        base_commit="$(base_commit_for "$blob" "$f" || true)"
        if [ -n "$base_commit" ]; then
            base_commit_by_file[$f]="$base_commit"
            out "patch base for $f: blob $blob"
            out "  commit $base_commit $(git log -1 --format='%s' "$base_commit" 2>/dev/null || true)"
        else
            out "patch base for $f: blob $blob"
            out "  commit unknown (no commit in this clone has this file at that blob)"
        fi
        out ""
    done
fi

# --------------------------------------------------------------------------
# Upstream commits since the base. When the base commit is known this is a
# range; when it is not, recent history for the file is still useful evidence,
# but it is labelled as such rather than presented as "everything that moved".
# --------------------------------------------------------------------------
out "Upstream commits since the patch base:"
for f in "${base_files[@]}"; do
    base_commit="${base_commit_by_file[$f]:-}"
    out "  $f:"
    if [ -z "$base_commit" ]; then
        if [ "$PIN" = "<unknown>" ]; then
            out "    pin unknown; no upstream history to inspect"
            out ""
            continue
        fi
        out "    base unknown; recent commits touching this file:"
        recent="$(git log --oneline --no-decorate -20 "$PIN" -- "$f" 2>/dev/null || true)"
        if [ -n "$recent" ]; then
            printf '%s\n' "$recent" | sed 's/^/      /'
        else
            out "      (none found)"
        fi
        out ""
        continue
    fi
    if git merge-base --is-ancestor "$base_commit" "$PIN" 2>/dev/null; then
        out "    $base_commit..$PIN"
        commits="$(git log --oneline --no-decorate "$base_commit..$PIN" -- "$f" 2>/dev/null || true)"
    else
        out "    base commit is not an ancestor of the pin; recent commits touching this file:"
        commits="$(git log --oneline --no-decorate -20 "$PIN" -- "$f" 2>/dev/null || true)"
    fi
    if [ -n "$commits" ]; then
        printf '%s\n' "$commits" | sed 's/^/      /'
    else
        out "      (none: no upstream commit touches this file in that range)"
    fi
    out ""
done

# --------------------------------------------------------------------------
# 2. The conflicting files, with the conflict itself.
# --------------------------------------------------------------------------
if [ "${#conflicted[@]}" -gt 0 ]; then
    out "Conflicted files:"
    printf '%s\n' "${conflicted[@]}" | sed 's/^/  /'
    out ""
    for f in "${conflicted[@]}"; do
        rule
        out "$f"
        rule
        # The markers are the whole diagnosis: HEAD is upstream's line, the other
        # side is what the port wanted in its place.
        awk '/^<<<<<<</{p=1} p{print} /^>>>>>>>/{p=0}' "$f" 2>/dev/null | sed 's/^/  /'
    done
fi

# --------------------------------------------------------------------------
# 3. What upstream did to those files, which is usually the actual explanation.
# A diff against the base commit is the honest comparison: HEAD includes our
# conflict markers, so diffing the worktree against the pin shows our own
# unresolved edit, not upstream's change.
# --------------------------------------------------------------------------
for f in "${conflicted[@]}"; do
    rule
    out "upstream changes to $f since the patch base"
    rule
    base_commit="${base_commit_by_file[$f]:-}"
    blob="${base_blob_by_file[$f]:-}"
    if [ -n "$base_commit" ]; then
        upstream_diff="$(git diff --no-color "$base_commit" "$PIN" -- "$f" 2>/dev/null | head -60 | sed 's/^/  /' || true)"
    elif [ -n "$blob" ]; then
        if [ "$PIN" = "<unknown>" ]; then
            upstream_diff=""
            out "  (no diff: the pin is unknown)"
        elif git cat-file -e "$PIN:$f" 2>/dev/null; then
            out "  (patch base commit unknown; diff is base blob -> pinned file)"
            upstream_diff="$(git diff --no-color "$blob" "$PIN:$f" 2>/dev/null | head -60 | sed 's/^/  /' || true)"
        else
            upstream_diff=""
            out "  (the file is absent from the pin; upstream deleted it)"
        fi
    else
        upstream_diff=""
    fi
    if [ -n "$upstream_diff" ]; then
        printf '%s\n' "$upstream_diff"
    elif [ -z "$base_commit" ] && [ -z "$blob" ]; then
        out "  (no diff: the patch base is unknown)"
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
