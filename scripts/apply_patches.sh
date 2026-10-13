#!/usr/bin/env bash
# Clone the pinned BepInEx upstream and replay the local patch queue
# (patches/*.patch) onto it, into a scratch directory under build/.
#
# The queue is the authoritative description of how upstream BepInEx is adapted to
# Android, and each entry is a `git format-patch` mail. Replaying it with `git am`
# (rather than `git apply`) keeps the three properties the trust story depends on:
#
#   - the result is a chain of commits, so what CI built can be diffed commit by
#     commit against what upstream did;
#   - `index` lines are present, so `git am --3way` can carry the port across an
#     upstream move instead of silently degrading;
#   - a patch that does not apply fails the build. There is no post-hoc `rm` step
#     that can paper over a missing hunk.
#
# On what --3way actually survives, measured rather than assumed:
#
#   - upstream edits elsewhere in a file: applies cleanly.
#   - upstream deletes a block the patch also deletes: applies cleanly.
#   - upstream changes a line the patch *rewrites*: CONFLICT. This is the case
#     that ends the queue, and it is a normal consequence of upstream moving,
#     not a malformed patch.
#
# So a conflict is an expected event on a sync, not a bug to be designed away.
# What must not happen is a conflict passing silently, which is why the failure
# path reports the file, the line and upstream's side.
#
# The previous version applied the queue in place inside the third_party/BepInEx
# submodule and refused to run on a dirty tree, so a build mutated the checkout it
# was reading. This version owns its scratch tree and leaves the repository
# untouched, which is what lets the submodule be deleted afterwards without any
# build having left residue in it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

PIN_FILE="$BEPINEX_PIN_FILE"
[ -f "$PIN_FILE" ] || { echo "missing pin file: $PIN_FILE" >&2; exit 1; }

# Sourced in a subshell: the pin file carries more than the pin, and some of those
# names are shared with the other provenance/*.env files.
read_pin() {
    (
        set -a; . "$PIN_FILE"; set +a
        : "${UPSTREAM_URL:?UPSTREAM_URL missing from $PIN_FILE}"
        : "${UPSTREAM_COMMIT:?UPSTREAM_COMMIT missing from $PIN_FILE}"
        printf '%s\n%s' "$UPSTREAM_URL" "$UPSTREAM_COMMIT"
    )
}

UPSTREAM_URL="$(read_pin | sed -n 1p)"
PIN="$(read_pin | sed -n 2p)"

# A fresh clone every run. Reusing the previous tree would make the result depend
# on what happened to be there, which is the non-repeatability this replaces.
rm -rf "$BEPINEX_SRC"
mkdir -p "$(dirname "$BEPINEX_SRC")"

echo "cloning $UPSTREAM_URL at ${PIN:0:10} -> ${BEPINEX_SRC#"$ROOT"/}"
git clone --quiet "$UPSTREAM_URL" "$BEPINEX_SRC"
git -C "$BEPINEX_SRC" checkout --quiet --detach "$PIN"

# Load-bearing, not a nicety. A gitlink is verified by the checkout that fetches
# it: the git plumbing must produce that commit or the checkout fails. A URL clone
# retrieves whatever the remote currently serves, so without this the pin would be
# an unchecked assertion - a rewritten branch, or a commit that merely looks like
# the pin, would be built and shipped under a manifest saying it is the pinned
# tree. Same assertion sync-upstream.yml applies to the submodule.
HEAD_SHA="$(git -C "$BEPINEX_SRC" rev-parse HEAD)"
if [ "$HEAD_SHA" != "$PIN" ]; then
    echo "ERROR: cloned $HEAD_SHA but the pin is $PIN" >&2
    echo "ERROR: refusing to build; this is not the pinned tree" >&2
    exit 1
fi

cd "$BEPINEX_SRC"

shopt -s nullglob
# Unquoted on purpose: this is a glob pattern, and quoting it here would make the
# literal string "patches/*.patch" the single entry - which is not a patch that
# failed to apply, it is a patch that does not exist, and it reads identically to
# a broken queue. If an override ever supplies a literal filename, the nullglob
# below still yields exactly that one file.
patches=($BEPINEX_PATCH_GLOB)
if [ "${#patches[@]}" -eq 0 ]; then
    echo "no patches match $BEPINEX_PATCH_GLOB" >&2
    exit 1
fi

applied=0
for p in "${patches[@]}"; do
    echo "applying $(basename "$p")"
    # git am mints a commit from each patch's author, which needs a committer
    # identity. A GitHub runner has none configured, and without this the very
    # first patch fails with "empty ident name not allowed" before touching a
    # file - a failure that looks exactly like a broken patch queue and is not
    # one. The name is only ever recorded in these throwaway commits, which are
    # never pushed and never recorded as a pointer anywhere.
    if [ -z "$(git config user.name || true)" ] ||
       [ -z "$(git config user.email || true)" ]; then
        git config user.name "${GIT_COMMITTER_NAME:-BepInEx Android port}"
        git config user.email "${GIT_COMMITTER_EMAIL:-bepinex-android@localhost}"
    fi

    if ! git am --3way "$p" >/dev/null; then
        # `git am` reports only that a patch failed. On a scheduled CI run
        # nothing collects the conflict, so the run dies with a filename and
        # nobody can tell which file, which line, or what upstream did. The
        # reporter prints that and, on a runner, writes it to the job summary.
        "$ROOT/scripts/report_patch_conflict.sh" "$p" "$applied" || true
        exit 1
    fi
    applied=$((applied + 1))
done

echo "patch queue applied ($(basename "${patches[0]}") .. $(basename "${patches[-1]}"))"

# NOTE: The NuGet lock files in references/nuget-locks are deliberately NOT
# staged into the scratch tree. NuGet validates a packages.lock.json found on
# disk even without RestoreLockedMode or RestorePackagesWithLockFile, and the
# locally-built MonoMod/Il2CppInterop packages are rebuilt on every run, so
# their contentHash can never match a vendored file - staging them fails every
# restore with NU1403. Version drift is still loud: restore_and_build.sh runs
# scripts/verify_locked_versions.sh after restore, which compares the restored
# versions against the vendored lock files without requiring the hashes to
# match. The lock directory must still exist; its absence means the version
# manifest is gone, which is fatal.
LOCK_SRC="$ROOT/references/nuget-locks/bepinex"
[ -d "$LOCK_SRC" ] || { echo "missing NuGet lock directory: $LOCK_SRC" >&2; exit 1; }

# Fail-closed patch gate. `git am` mints commits with committer dates of "now",
# so the tip SHA is never comparable across runs; the tree object it points at
# is the stable content hash of the patched sources. A queue edit that drops
# or reorders a patch, or a --3way resolution that lands differently, changes
# the tree, and the build must not proceed on an unrecorded one. Deliberate
# queue changes re-record the hash with scripts/record_patched_tree.sh.
#
# The one caller that skips the gate is the sync-upstream job: it advances the
# pin first, so the tree is *supposed* to differ, and it re-records the hash
# with record_patched_tree.sh immediately after this script. The skip is
# opt-in per invocation (BEPINEX_SKIP_TREE_GATE=1), never a default, and the
# re-recorded hash still has to survive the gate on the sync PR's own CI run.
if [ "${BEPINEX_SKIP_TREE_GATE:-0}" = "1" ]; then
    echo "tree gate skipped (BEPINEX_SKIP_TREE_GATE=1); re-record with scripts/record_patched_tree.sh" >&2
else
    "$ROOT/scripts/verify_patches.sh" "$BEPINEX_SRC" "$PIN" "$ROOT/references/patched-tree.sha256"
fi

echo "BEPINEX_SRC=$BEPINEX_SRC"
