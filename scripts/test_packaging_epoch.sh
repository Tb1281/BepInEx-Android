#!/usr/bin/env bash
# Fixture: the framework zip's epoch comes from the upstream pin, not the tip.
#
# WHY THIS EXISTS
#
# package_bepinex_android.sh pins the archive epoch to "the BepInEx pin's
# commit date" so identical inputs package to identical bytes (and
# provenance/sha256sums.txt can hold). It once read the date from the tip
# commit instead - but the tip is a `git am` product whose committer date is
# "now" on every fresh clone, so every CI run produced a different
# BepInEx.Android.zip and the manifest could never match. A future edit that
# "simplifies" the epoch back to `git log -1` reintroduces the drift without
# failing any build; it just makes the manifest unmatchable again.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

fails=0

# 1. The script must read the epoch from the pin commit ($UPSTREAM_PIN),
#    never from the bare tip.
if grep -q 'log -1 --format=%ct "\$UPSTREAM_PIN"' scripts/package_bepinex_android.sh; then
    echo "ok    epoch is read from \$UPSTREAM_PIN"
else
    echo "FAIL  epoch is not read from \$UPSTREAM_PIN"
    fails=$((fails + 1))
fi

# 2. Behavioral check: in a scratch-like tree (pin commit, then a newer
#    git-am tip), the pin date and the tip date differ, and the epoch query
#    the script uses returns the pin date.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git -C "$TMP" init -q
git -C "$TMP" config user.email t@t
git -C "$TMP" config user.name t
GIT_AUTHOR_DATE="2026-09-01T05:03:47Z" GIT_COMMITTER_DATE="2026-09-01T05:03:47Z" \
    git -C "$TMP" commit -q --allow-empty -m pin
PIN_SHA="$(git -C "$TMP" rev-parse HEAD)"
PIN_DATE="$(git -C "$TMP" log -1 --format=%ct "$PIN_SHA")"
sleep 1
git -C "$TMP" commit -q --allow-empty -m tip
TIP_DATE="$(git -C "$TMP" log -1 --format=%ct)"
if [ "$PIN_DATE" = "$TIP_DATE" ]; then
    echo "FAIL  fixture setup: pin and tip dates are equal"
    fails=$((fails + 1))
else
    EPOCH="$(git -C "$TMP" log -1 --format=%ct "$PIN_SHA" 2>/dev/null || true)"
    if [ "$EPOCH" = "$PIN_DATE" ]; then
        echo "ok    pin-date query returns the pin date, not the tip date"
    else
        echo "FAIL  pin-date query returned $EPOCH, want $PIN_DATE"
        fails=$((fails + 1))
    fi
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
