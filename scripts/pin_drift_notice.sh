#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - leave a durable record when an upstream pin has drifted
# ==============================================================================
#
# WHY THIS EXISTS
#
# Every upstream pin is watched: check_upstream_pins.sh asks each
# provenance/*.env pin whether its ref moved, and check_runtime_pin.sh asks
# Microsoft whether the .NET channel moved. Both report to the step summary
# of a green run, which nobody opens on a green run - so drift is detected
# and simultaneously invisible. ci_failure_notice.sh exists because a failing
# scheduled job "is visible to whoever watches CI"; drift on a passing job is
# the same shape one step removed.
#
# WHAT IT DOES NOT DO
#
# It never moves a pin. docs/todo.md Rule 1: pins are moved by a human. A
# stale runtime pin SIGABRTs on the device, a stale MonoMod pin fails every
# Harmony patch, and a stale Cpp2IL pin changes interop output - none of that
# is visible to any gate, so bumping is news followed by a person and this
# script only produces the news. The issue body points at the pin files and
# their bump procedures rather than bumping anything.
#
# WHAT "ROLLING" MEANS HERE
#
# One open issue, title fixed, body replaced rather than appended to - the
# same contract as ci_failure_notice.sh. Closed when every watched pin reads
# current again.
# ==============================================================================
#
# USAGE
#   scripts/pin_drift_notice.sh <drifted|current> [report-file]
#
#   <report-file> is the captured stdout of the drift check (check_upstream_pins.sh
#   or check_runtime_pin.sh); its contents become the issue body. The caller
#   derives the state: check_upstream_pins.sh prints `  MOVED` / `  UNKNOWN`
#   lines, check_runtime_pin.sh prints `RESULT: moved` (or no RESULT at all on
#   a metadata outage, which is drifted - an upstream that cannot be asked
#   reads as unknown, never as current).
#
# Environment:
#   GITHUB_REPOSITORY   owner/name; required
#   GH_TOKEN            the token gh authenticates with (workflows pass
#                       `GH_TOKEN: ${{ github.token }}` on the step).
#   GITHUB_RUN_ID / GITHUB_REF_NAME / GITHUB_SHA   defaulted, never required.
#
# Exit status is always 0. A notification step that fails would replace a
# recorded drift report with a less informative failure.
#
# The `pin-drift` label is created once by hand (like `ci-failure`):
#   gh label create pin-drift --description "Rolling record of upstream pin drift (managed by pin_drift_notice.sh)" --color B60205

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 0

: "${GH_TOKEN:=${GITHUB_TOKEN:-}}"
export GH_TOKEN
if [ -z "$GH_TOKEN" ]; then
    echo "::warning::no GH_TOKEN and no GITHUB_TOKEN; cannot file or close a record"
fi

LABEL="pin-drift"

fail() { echo "pin_drift_notice: $*" >&2; }

state="${1:-}"
report="${2:-}"

if [ -z "$state" ]; then
    fail "usage: $0 <drifted|current> [report-file]"
    exit 0
fi

case "$state" in
drifted|current) ;;
*) fail "state must be 'drifted' or 'current', got '${state}'"; exit 0 ;;
esac

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

RUN_ID="${GITHUB_RUN_ID:-unknown}"
REF_NAME="${GITHUB_REF_NAME:-unknown}"
REF_SHA="${GITHUB_SHA:-unknown}"

TITLE="Upstream pin drift"

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

build_body() {
    {
        echo "One or more upstream pins have drifted from what this repository builds."
        echo ""
        echo "| | |"
        echo "|---|---|"
        echo "| run | [#${RUN_ID}](https://github.com/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}) |"
        echo "| branch | \`${REF_NAME}\` |"
        echo "| commit | [\`${REF_SHA:0:12}\`](https://github.com/${GITHUB_REPOSITORY}/commit/${REF_SHA}) |"
        echo ""
        echo "Pins move by hand (docs/todo.md Rule 1): the bump procedures live at"
        echo "the top of each \`provenance/*.env\` file. This issue is replaced by"
        echo "each new report and closed when every watched pin reads current."
        echo ""
        echo "### Drift report"
        echo ""
        echo '```'
        if [ -n "$report" ] && [ -f "$report" ]; then
            # Cap the report: the pin table is short, but a metadata outage can
            # dump a curl error plus python traceback above it.
            head -c 6000 "$report"
        else
            echo "(no report file was handed to the notice step)"
        fi
        echo '```'
    } > "$BODY"
}

case "$state" in
drifted)
    build_body

    existing="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" \
        --state open --json number,title --limit 50 \
        --jq ".[] | select(.title == \"${TITLE}\") | .number" 2>/dev/null | head -1)"
    printf '%s' "$existing" | grep -qE '^[0-9]+$' || existing=""

    if [ -n "$existing" ]; then
        if gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" --body-file "$BODY" \
            >/dev/null 2>&1; then
            echo "updated pin_drift_notice issue #${existing}"
        else
            fail "could not update issue #${existing}"
        fi
    else
        if gh issue create --repo "$GITHUB_REPOSITORY" --title "$TITLE" \
            --label "$LABEL" --body-file "$BODY" >/dev/null 2>&1; then
            echo "opened a new pin_drift_notice issue"
        else
            fail "could not create an issue (is the '${LABEL}' label missing, or issues: write not granted?)"
        fi
    fi
    ;;
current)
    open="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" \
        --state open --json number,title --limit 50 \
        --jq ".[] | select(.title == \"${TITLE}\") | .number" 2>/dev/null \
        | grep -E '^[0-9]+$' || true)"
    if [ -z "$open" ]; then
        echo "no open pin_drift_notice"
    else
        while read -r num; do
            [ -n "$num" ] || continue
            if gh issue close "$num" --repo "$GITHUB_REPOSITORY" \
                --comment "Every watched pin reads current again. Closing." >/dev/null 2>&1; then
                echo "closed pin_drift_notice issue #${num}"
            else
                fail "could not close issue #${num}"
            fi
        done <<<"$open"
    fi
    ;;
esac

if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ "$state" = drifted ]; then
    {
        echo "### Pin drift notice"
        echo ""
        cat "$BODY"
    } >>"$GITHUB_STEP_SUMMARY"
fi

exit 0
