#!/usr/bin/env bash
# Advance the BepInEx pin to a publicly fetchable *upstream* commit.
# The patch queue is replayed only inside CI's scratch tree; no generated
# Android-port commits may ever be recorded as a pin in this repo.
#
# Writes GITHUB_OUTPUT (moved/old/new) when running under Actions, so the
# workflow can tell "upstream moved" from "nothing to do". Both are normal
# outcomes and only the first warrants a PR.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/bepinex_paths.sh
. "$ROOT/scripts/bepinex_paths.sh"

PIN_FILE="$BEPINEX_PIN_FILE"
[ -f "$PIN_FILE" ] || { echo "missing pin file: $PIN_FILE" >&2; exit 1; }

if [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ] &&
   git -C "$ROOT" status --porcelain --untracked-files=normal | grep -q "^ M $PIN_FILE"; then
    echo "refusing to sync: $PIN_FILE has uncommitted changes" >&2
    git -C "$ROOT" status --short >&2
    exit 1
fi

OLD="$(sed -n 's/^UPSTREAM_COMMIT=//p' "$PIN_FILE" | head -1)"
URL="$(sed -n 's/^UPSTREAM_URL=//p' "$PIN_FILE" | head -1)"
[ -n "$OLD" ] || { echo "no UPSTREAM_COMMIT in $PIN_FILE" >&2; exit 1; }
[ -n "$URL" ] || { echo "no UPSTREAM_URL in $PIN_FILE" >&2; exit 1; }

# Query upstream in a throwaway clone. The pin is a text file now, so there is no
# submodule to fetch into - and the repository must stay clean, because the only
# thing this job stages is one line of that file.
FETCH_DIR="$ROOT/build/bepinex-probe"
rm -rf "$FETCH_DIR"
git clone --quiet "$URL" "$FETCH_DIR"
NEW="$(git -C "$FETCH_DIR" rev-parse origin/master)"
rm -rf "$FETCH_DIR"

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
    echo "checked $(git -C "$ROOT" log -1 --format='%s' "$NEW" 2>/dev/null || echo "$NEW")"
    record false "$OLD" "$NEW"
    exit 0
fi

# OLD is a pristine upstream commit. CI subsequently clones it, replays the
# patches and builds, then stages the new pin.
#
# Only the UPSTREAM_COMMIT line is rewritten. sed -i is scoped to that one line
# so the explanatory comments above it - which record why this dependency is
# pinned and what breaks if it moves - survive the bump intact.
sed -i "s|^UPSTREAM_COMMIT=.*|UPSTREAM_COMMIT=$NEW|" "$PIN_FILE"
cd "$ROOT"
git add "$PIN_FILE"
echo "upstream moved: $OLD -> $NEW (pin updated in ${PIN_FILE#"$ROOT"/})"
record true "$OLD" "$NEW"