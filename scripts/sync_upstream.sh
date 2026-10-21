#!/usr/bin/env bash
# Advance the BepInEx pin to a publicly fetchable *upstream* commit.
# The patch queue is replayed only inside CI's scratch tree; no generated
# Android-port commits may ever be recorded as a pin in this repo.
#
# Writes GITHUB_OUTPUT (moved/old/new) when running under Actions, so the
# workflow can tell "upstream moved" from "nothing to do". Both are normal
# outcomes and only the first warrants a PR.
#
# The pin file is the interface: the repository this script changes is the one
# that contains that file, not wherever the script happens to live. That makes
# the default behavior identical while letting the fixture point
# BEPINEX_PIN_FILE at a throwaway repository. Before this, the dirty check
# compared an absolute pin path against repo-relative `git status` output, so
# it could never match, and the bump staged the file from the wrong directory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

PIN_FILE="$BEPINEX_PIN_FILE"
[ -f "$PIN_FILE" ] || { echo "missing pin file: $PIN_FILE" >&2; exit 1; }
# Normalize a relative override before deriving the repository and the
# repo-relative pathspec. The default is absolute; a caller passing
# provenance/bepinex.env should still work.
PIN_FILE="$(cd "$(dirname "$PIN_FILE")" && pwd)/$(basename "$PIN_FILE")"

REPO_ROOT="$(git -C "$(dirname "$PIN_FILE")" rev-parse --show-toplevel 2>/dev/null)" || {
    echo "refusing to sync: $PIN_FILE is not inside a git repository" >&2
    exit 1
}
PIN_REL="${PIN_FILE#"$REPO_ROOT"/}"
[ "$PIN_REL" != "$PIN_FILE" ] || {
    echo "refusing to sync: $PIN_FILE is outside $REPO_ROOT" >&2
    exit 1
}

# Only the pin file is this script's concern. An unrelated dirty file elsewhere
# in the repository is not something a pin bump can overwrite; the old check
# looked at the whole tree and then tried to match the absolute pin path,
# which made it both too broad and unable to match.
pin_status="$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal -- "$PIN_REL")"
if [ -n "$pin_status" ]; then
    echo "refusing to sync: $PIN_REL has uncommitted changes" >&2
    printf '%s\n' "$pin_status" >&2
    exit 1
fi

OLD="$(sed -n 's/^UPSTREAM_COMMIT=//p' "$PIN_FILE" | head -1)"
URL="$(sed -n 's/^UPSTREAM_URL=//p' "$PIN_FILE" | head -1)"
REF="$(sed -n 's/^UPSTREAM_REF=//p' "$PIN_FILE" | head -1)"
[ -n "$OLD" ] || { echo "no UPSTREAM_COMMIT in $PIN_FILE" >&2; exit 1; }
[ -n "$URL" ] || { echo "no UPSTREAM_URL in $PIN_FILE" >&2; exit 1; }
[ -n "$REF" ] || { echo "no UPSTREAM_REF in $PIN_FILE" >&2; exit 1; }

# Query upstream in a throwaway clone. The pin is a text file now, so there is
# no submodule to fetch into - and the repository must stay clean, because the
# only thing this job stages is one line of that file. The probe lives outside
# the repository: a failed clone must not leave a directory that makes the next
# run depend on what the previous one left behind.
FETCH_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bepinex-probe.XXXXXX")"
trap 'rm -rf "$FETCH_DIR"' EXIT
git clone --quiet "$URL" "$FETCH_DIR"
NEW="$(git -C "$FETCH_DIR" rev-parse "origin/$REF" 2>/dev/null)" || {
    echo "refusing to sync: $URL has no refs/heads/$REF" >&2
    exit 1
}
# The description is part of the pin's human-readable identity. Leaving it
# stale after a bump makes the next reader compare a new SHA against an old
# tag and conclude the wrong tree was built.
NEW_DESCRIBE="$(git -C "$FETCH_DIR" describe --tags --always "$NEW" 2>/dev/null || printf '%s' "$NEW")"

record() {
    [ -n "${GITHUB_OUTPUT:-}" ] || return 0
    {
        echo "moved=$1"
        echo "old=$2"
        echo "new=$3"
    } >>"$GITHUB_OUTPUT"
}

if [ "$OLD" = "$NEW" ]; then
    # Being at upstream HEAD is the expected steady state, not a result worth
    # announcing. It is reported here so the step log records which commit was
    # checked, and the workflow suppresses the PR.
    echo "upstream unchanged ($NEW)"
    echo "checked $(git -C "$FETCH_DIR" log -1 --format='%s' "$NEW" 2>/dev/null || echo "$NEW")"
    record false "$OLD" "$NEW"
    exit 0
fi

# OLD is a pristine upstream commit. CI subsequently clones it, replays the
# patches and builds, then stages the new pin.
#
# Only the pin lines are rewritten. sed -i is scoped to those lines so the
# explanatory comments above them - which record why this dependency is
# pinned and what breaks if it moves - survive the bump intact.
sed -i "s|^UPSTREAM_COMMIT=.*|UPSTREAM_COMMIT=$NEW|" "$PIN_FILE"
if grep -q '^UPSTREAM_DESCRIBE=' "$PIN_FILE"; then
    sed -i "s|^UPSTREAM_DESCRIBE=.*|UPSTREAM_DESCRIBE=$NEW_DESCRIBE|" "$PIN_FILE"
else
    printf '\nUPSTREAM_DESCRIBE=%s\n' "$NEW_DESCRIBE" >>"$PIN_FILE"
fi
git -C "$REPO_ROOT" add -- "$PIN_REL"
echo "upstream moved: $OLD -> $NEW (pin updated in $PIN_REL)"
record true "$OLD" "$NEW"
