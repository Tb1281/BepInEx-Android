#!/usr/bin/env bash
# Fixture for scripts/check_runtime_pin.sh.
#
# The script's job is a comparison against a live metadata document, so the
# document is stubbed and the four states that matter are pinned:
#
#   - the pin is current          -> says current, exit 0
#   - the channel moved           -> says moved, names the procedure, exit 0
#   - the index cannot be read    -> says currency unknown, exit 0, claims nothing
#   - the index cannot be parsed  -> same
#
# The third case is the one that matters most. A check that cannot run must not
# report "current": that is the fail-open shape this repository has been removing
# one at a time, and it would be easy to reintroduce here by treating an empty
# response as "nothing changed".
#
# It also asserts that a moved runtime does NOT fail, because a newer runtime is
# news rather than a defect, and a weekly job that fails for it is a weekly job
# people stop reading.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/runtime-pin.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/check_runtime_pin.sh"

fails=0
expect() { # expect <want-rc> <label> <needle...>
    local want="$1" label="$2"; shift 2
    local out rc needle
    out="$("$SCRIPT" 2>&1)"; rc=$?
    local ok=1
    [ "$rc" -eq "$want" ] || ok=0
    for needle in "$@"; do
        printf '%s' "$out" | grep -qF -- "$needle" || ok=0
    done
    if [ "$ok" = 1 ]; then
        echo "ok    $label (exit $rc)"
    else
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
}

index_with() { # index_with <latest-runtime> [latest-sdk]
    cat <<JSON
{"releases-index":[
 {"channel-version":"10.0","latest-sdk":"${2:-10.0.401}","latest-runtime":"$1","support-phase":"active"},
 {"channel-version":"9.0","latest-sdk":"9.0.401","latest-runtime":"9.0.12","support-phase":"active"}
]}
JSON
}

echo "== check_runtime_pin.sh =="
echo "   pinned version: $(grep '^DOTNET_RUNTIME_VERSION=' "$REPO/provenance/dotnet-runtime.env" | cut -d= -f2)"
echo

PINNED="$(grep '^DOTNET_RUNTIME_VERSION=' "$REPO/provenance/dotnet-runtime.env" | cut -d= -f2)"

# 1. current
index_with "$PINNED" > "$WORK/current.json"
RELEASES_INDEX="file://$WORK/current.json" expect 0 "current pin is reported as current" \
    "RESULT: current" "$PINNED"

# 2. moved - newer runtime published. Must NOT fail, and must say what to do.
index_with "10.0.99" > "$WORK/moved.json"
RELEASES_INDEX="file://$WORK/moved.json" expect 0 "newer runtime is reported without failing" \
    "RESULT: moved" "10.0.99" "pal-java-contract.txt" "MonoMod.Core"

# 3. index unreachable - must not claim current
RELEASES_INDEX="file://$WORK/does-not-exist.json" expect 0 "unreadable index claims nothing" \
    "currency unknown" "metadata outage"

# 4. index unparseable - must not claim current
printf 'not json at all {{{' > "$WORK/bad.json"
RELEASES_INDEX="file://$WORK/bad.json" expect 0 "unparseable index claims nothing" \
    "currency unknown"

# 5. channel absent entirely
printf '{"releases-index":[{"channel-version":"8.0","latest-runtime":"8.0.0","support-phase":"eol"}]}' > "$WORK/nochan.json"
RELEASES_INDEX="file://$WORK/nochan.json" expect 0 "absent channel claims nothing" \
    "currency unknown"

# 6. it must not pick a different channel's version: the index has 9.0 and 10.0,
#    and the pin is on 10.0, so 9.0.12 must never appear as the comparison.
index_with "$PINNED" > "$WORK/multichan.json"
out="$(RELEASES_INDEX="file://$WORK/multichan.json" "$SCRIPT" 2>&1)"
if printf '%s' "$out" | grep -qF "latest : $PINNED"; then
    echo "ok    selects the pinned channel, not another one"
elif printf '%s' "$out" | grep -qF "9.0.12"; then
    echo "FAIL  picked another channel's runtime:"
else
    echo "FAIL  selected the wrong channel:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 7. the report must reach the step summary, which is where a weekly run is read
SUMMARY="$WORK/summary.md"; : > "$SUMMARY"
RELEASES_INDEX="file://$WORK/moved.json" GITHUB_STEP_SUMMARY="$SUMMARY" "$SCRIPT" >/dev/null 2>&1
if grep -q "moved" "$SUMMARY"; then
    echo "ok    report is written to GITHUB_STEP_SUMMARY"
else
    echo "FAIL  nothing written to GITHUB_STEP_SUMMARY:"
    sed 's/^/        /' "$SUMMARY"
    fails=$((fails + 1))
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"