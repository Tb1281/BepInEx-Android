#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - mark the commit that last passed the gate
# ==============================================================================
#
# WHAT THIS IS FOR
#
# Goal 5 of this project is that a failure should leave two things legible: the
# last state that was verified good, and the reason for the failure. The reason is
# already produced - report_patch_conflict.sh writes the patch, its position in
# the queue, the conflicted file, both sides of the conflict and the upstream diff
# into the step summary.
#
# The "last state that was verified good" did not exist anywhere. The checksum
# manifest records what was built, and the run history records what passed, but
# both are read by scrolling. Nothing in the repository said "this commit is the
# one everything green was true of", so the question had to be answered by
# running CI and reading it.
#
# This moves a tag named `verified` to the commit that just passed. It is a
# deliberately moving pointer rather than an immutable release tag: the semantic
# is "the most recent commit known good", which by nature advances.
#
#     git describe --tags --abbrev=0 verified
#
# It is moved and force-moved without ceremony, so it needs no changelog entry and
# produces no PR noise.
#
# WHY IT REFUSES TO RUN OFF THE DEFAULT BRANCH
#
# A gate that ran on a feature branch and tagged it would advertise unreviewed work
# as verified. Every run of this script on a branch is therefore a no-op, and it
# says so, because a marker that silently records the wrong thing is worse than no
# marker.
#
# Note what this is not: it is not an attestation and not a signature. It records
# that a workflow reported success, on a branch that already exists in the history,
# by the same token that ran the gate. It answers "which commit was last green", not
# "who published this".
# ==============================================================================
#
# USAGE
#   scripts/mark_verified.sh            move the tag (no-op unless allowed to)
#   scripts/mark_verified.sh --dry-run  report the decision and exit
#
# Environment:
#   GITHUB_REF              e.g. refs/heads/main
#   GITHUB_DEFAULT_BRANCH   defaults to main
#   GITHUB_SHA              commit to mark; defaults to HEAD
#   TAG_NAME                defaults to verified

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# gh does not read GITHUB_TOKEN, and on a runner GITHUB_TOKEN is not in the
# environment either unless the step asks for it - so the workflow passes
# GH_TOKEN: ${{ github.token }} to this step. Falling back to GITHUB_TOKEN keeps a
# local run working without duplicating the caller's setup.
#
# Both halves of that were wrong once. The first version printed "[NEW]" and then
# failed the write with exit 4; the second failed earlier with "no GH_TOKEN and no
# GITHUB_TOKEN", because exporting GITHUB_TOKEN from nothing does not conjure one.
# A stubbed gh in a fixture cannot catch either - a stub does not care about
# credentials - so the fixture has the stub record the environment it actually
# sees, and there is a case for running with no token at all.
: "${GH_TOKEN:=${GITHUB_TOKEN:-}}"
export GH_TOKEN
if [ -z "$GH_TOKEN" ]; then
    echo "::error::no GH_TOKEN and no GITHUB_TOKEN; cannot move ${TAG_NAME:-verified}"
    exit 1
fi

TAG_NAME="${TAG_NAME:-verified}"
DEFAULT_BRANCH="${GITHUB_DEFAULT_BRANCH:-main}"
CURRENT_REF="${GITHUB_REF:-}"
TARGET_SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        *) echo "unknown option: ${arg}" >&2; exit 2 ;;
    esac
done

echo "============================================================"
echo " Mark the last verified commit                            "
echo "============================================================"
echo "  tag      : ${TAG_NAME}"
echo "  branch   : ${DEFAULT_BRANCH}"
echo "  ref      : ${CURRENT_REF:-<unset>}"
echo "  commit   : ${TARGET_SHA}"

# --- guard 1: only the default branch may move the marker -------------------
if [ "$CURRENT_REF" != "refs/heads/$DEFAULT_BRANCH" ]; then
    echo ""
    echo "  [SKIP] not a run on ${DEFAULT_BRANCH}."
    echo "         A gate that ran on a branch would be recording unreviewed work as"
    echo "         verified, so this marker only ever moves on the default branch."
    exit 0
fi

# --- guard 2: the commit must actually be in this repository ---------------
# Defends against a GITHUB_SHA that is not reachable, which would otherwise leave
# a tag pointing at nothing.
if ! git cat-file -e "${TARGET_SHA}^{commit}" 2>/dev/null; then
    echo "::error::${TARGET_SHA} is not a commit in this checkout; refusing to move ${TAG_NAME}"
    exit 1
fi

# --- where the tag is now ----------------------------------------------------
# Validated rather than trusted, and the validation is not optional. When the ref
# does not exist, `gh api` writes the API error body to *stdout* and exits 1, so
# `2>/dev/null || true` leaves a JSON document in this variable rather than an
# empty string - and the first real run on main therefore took the "the tag is
# somewhere else" branch, tried to PATCH it, and printed a JSON fragment as a
# commit id. The fixture had asserted otherwise, because its stub exited 1 with
# empty stdout, which is not what gh does.
existing="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${TAG_NAME}" \
    --jq '.object.sha' 2>/dev/null || true)"
if ! printf '%s' "$existing" | grep -qE '^[0-9a-f]{7,40}$'; then
    existing=""
fi

if [ "$existing" = "$TARGET_SHA" ]; then
    echo ""
    echo "  [NO-OP] ${TAG_NAME} already points at ${TARGET_SHA:0:12}."
    exit 0
fi

if [ -n "$existing" ]; then
    echo "  [MOVE]  ${existing:0:12} -> ${TARGET_SHA:0:12}"
else
    echo "  [NEW]   ${TAG_NAME} -> ${TARGET_SHA:0:12}"
fi

if [ "$DRY_RUN" = 1 ]; then
    echo "  [DRY-RUN] nothing was written"
    exit 0
fi

# --- move it ----------------------------------------------------------------
# Created if absent, force-moved if present. Force is correct rather than
# convenient: the marker means "most recent good", so moving it is the whole
# point and a non-fast-forward here is normal operation, not a conflict.
if [ -n "$existing" ]; then
    gh api -X PATCH "repos/${GITHUB_REPOSITORY}/git/refs/tags/${TAG_NAME}" \
        -f sha="$TARGET_SHA" -F force=true --jq '.ref' >/dev/null
else
    gh api -X POST "repos/${GITHUB_REPOSITORY}/git/refs" \
        -f ref="refs/tags/${TAG_NAME}" -f sha="$TARGET_SHA" --jq '.ref' >/dev/null
fi

echo "  [OK]    ${TAG_NAME} now marks ${TARGET_SHA:0:12}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Last verified commit"
        echo ""
        echo "\`${TAG_NAME}\` -> [\`${TARGET_SHA:0:12}\`](../../commit/${TARGET_SHA})"
        echo ""
        echo "The gate passed on the default branch at this commit."
    } >>"$GITHUB_STEP_SUMMARY"
fi