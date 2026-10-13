#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - leave a durable record when a scheduled run fails
# ==============================================================================
#
# WHY THIS EXISTS
#
# provenance/SOURCES.md states the gap plainly: a failing scheduled job "is
# visible to whoever watches CI. There is no issue, mail or annotation." That is
# not a hypothetical cost. sync-upstream failed every six hours for three days
# because upstream force-pushed a commit away, and nothing said so until someone
# went looking.
#
# This is also not the same as GitHub's own run notifications, which are a
# personal account setting rather than anything the repository controls. A record
# inside the repository is visible to whoever has access, regardless of how their
# account is configured.
#
# WHAT IT COVERS, AND WHY ONLY SCHEDULED RUNS
#
# A run that was triggered by a push failed in front of whoever pushed. A run
# that was triggered by a cron schedule failed in front of nobody, which is why
# it can stay broken for days. So this is wired into the two scheduled workflows
# only, and the same script closes the record when they go green again.
#
# WHAT "ROLLING" MEANS HERE
#
# One open issue per workflow, title fixed, body replaced rather than appended to.
# Appending would turn a three-day outage into a hundred comments and bury the
# answer to "what is failing now". Replacing keeps the issue a statement about
# the present, which is what someone opening it wants.
#
# It is not an attestation and carries no authority. It is a pointer: here is the
# run, here is the step, here is what the tool printed.
# ==============================================================================
#
# USAGE
#   scripts/ci_failure_notice.sh <workflow-name> <failed|passed>
#
# Environment:
#   GITHUB_REPOSITORY   owner/name; required
#   GH_TOKEN            the token gh authenticates with. The workflows pass
#                       `GH_TOKEN: ${{ github.token }}` on this step, because gh
#                       reads neither GH_TOKEN nor GITHUB_TOKEN automatically and
#                       a runner does not export the latter into a step at all.
#   GITHUB_RUN_ID       the run this is reporting on
#   GITHUB_STEP_SUMMARY when set, the same report is appended for the run log
#
# Only GITHUB_REPOSITORY is required. Everything else is defaulted, because the
# script's contract is that it exits 0 - an `set -u` death on a missing
# GITHUB_SHA would replace a recorded failure with a different, less informative
# failure, which is the one outcome this file exists to prevent.
#
# Exit status is always 0. A notification step that fails would replace a
# recorded failure with a different, less informative one.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 0

# gh does not read GITHUB_TOKEN, and a runner does not export GITHUB_TOKEN into a
# step's environment unless the step asks for it. The workflow therefore passes
# GH_TOKEN: ${{ github.token }}; this fallback is for a local run.
#
# Both halves of that were wrong once: the first made every call here fail with
# exit 4, the second failed on "no GH_TOKEN and no GITHUB_TOKEN" because
# exporting GITHUB_TOKEN from nothing does not conjure one. Because this script
# always exits 0, the first of those was a silent no-op - the worst outcome
# available for a step whose whole job is to make failures visible.
: "${GH_TOKEN:=${GITHUB_TOKEN:-}}"
export GH_TOKEN
if [ -z "$GH_TOKEN" ]; then
    echo "::warning::no GH_TOKEN and no GITHUB_TOKEN; cannot file or close a record"
fi

LABEL="ci-failure"

fail() { echo "ci_failure_notice: $*" >&2; }

workflow="${1:-}"
state="${2:-}"

if [ -z "$workflow" ] || [ -z "$state" ]; then
    fail "usage: $0 <workflow-name> <failed|passed>"
    exit 0
fi

case "$state" in
failed|passed) ;;
*) fail "state must be 'failed' or 'passed', got '${state}'"; exit 0 ;;
esac

TITLE="CI: ${workflow} failed"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

# Every other piece of context is defaulted, not required. This script promises
# to exit 0 whatever happens, and `set -u` turning a missing GITHUB_SHA into
# exit 1 broke that promise before this was written - a notice step that dies on
# missing context cannot report the failure it exists to report.
RUN_ID="${GITHUB_RUN_ID:-unknown}"
REF_NAME="${GITHUB_REF_NAME:-unknown}"
REF_SHA="${GITHUB_SHA:-unknown}"

# --- the issue body: what failed, where, and what the tool said ---------------
#
# It is written to $BODY - the file handed to `gh issue create/edit --body-file` -
# and to nothing else.
#
# It used to be written to "$1.body": a path built from the conclusion and
# therefore relative, which resolved against the repository root this script cd's
# into at line 60. The report landed in a file in the working tree while
# --body-file received an untouched, empty mktemp file. Every gh call still
# succeeded and the script still exited 0, so the rolling issue opened with an
# empty body and the step summary got a heading with nothing under it: a durable
# record that said nothing, which is the one outcome this file exists to prevent.
# The fixture missed it because it only checked that --body-file was passed, not
# what was in it.
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

build_body() {
    local conclusion="$1"
    {
        echo "\`${workflow}\` is failing."
        echo ""
        echo "| | |"
        echo "|---|---|"
        echo "| workflow | [\`${workflow}\`](https://github.com/${GITHUB_REPOSITORY}/actions/workflows) |"
        echo "| run | [#${RUN_ID}](https://github.com/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}) |"
        echo "| concluded | \`${conclusion}\` |"
        echo "| branch | \`${REF_NAME}\` |"
        echo "| commit | [\`${REF_SHA:0:12}\`](https://github.com/${GITHUB_REPOSITORY}/commit/${REF_SHA}) |"
        echo ""
        echo "This issue is replaced by each new failure and closed when the workflow"
        echo "goes green, so the body always describes the current state."
    } > "$BODY"

    # The annotations are where the tool's own words are. Without them this is
    # only a pointer; with them, the common case is readable without opening the
    # log at all.
    local jobs jobsrc job_id step
    jobs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/jobs" \
        --jq '.jobs[] | select(.conclusion != "success" and .conclusion != "skipped")
               | "job=\(.name) id=\(.id)"' 2>/dev/null || true)"
    # Discard anything that is not a record we wrote. When a request fails, gh
    # prints the API error body on stdout and exits 1, so `|| true` leaves that
    # body in the variable - and it would otherwise be read as a job line, with
    # `id=` extracted out of a JSON document and passed to further API calls.
    jobs="$(printf '%s\n' "$jobs" | grep -E '^job=.* id=[0-9]+$' || true)"

    if [ -z "$jobs" ]; then
        {
            echo ""
            echo "No failing job was reported. The run may have been cancelled or"
            echo "timed out rather than failing a step."
        } >> "$BODY"
        return
    fi

    while IFS= read -r jobsrc; do
        job_id="${jobsrc##*id=}"
        job_id="${job_id%% *}"
        job="$(gh api "repos/${GITHUB_REPOSITORY}/actions/jobs/${job_id}" \
            --jq '.name' 2>/dev/null || echo "job ${job_id}")"
        step="$(gh api "repos/${GITHUB_REPOSITORY}/actions/jobs/${job_id}" \
            --jq '[.steps[] | select(.conclusion == "failure")] | map(.name) | join(", ")' \
            2>/dev/null || true)"
        {
            echo ""
            echo "### ${job}"
            [ -n "$step" ] && echo ""
            [ -n "$step" ] && echo "Failed step: \`${step}\`"
        } >> "$BODY"

        # annotations carry the error text; cap them so a run emitting thousands
        # of warnings cannot produce an issue nobody will read to the end.
        # The directive sits on the gh line because $path and $message are jq
        # fields, not shell variables, and must not be expanded.
        # shellcheck disable=SC2016
        gh api "repos/${GITHUB_REPOSITORY}/check-runs/${job_id}/annotations" \
            --jq '[.[] | select(.annotation_level == "failure")] | .[0:12][]
                   | "```\n" + .path + "\n" + .message + "\n```"' \
            2>/dev/null >> "$BODY" || true
    done <<<"$jobs"
}

case "$state" in
failed)
    build_body "failure"
    conclusion="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}" \
        --jq '.conclusion' 2>/dev/null || echo failure)"
    # Same reason as the job list above: a failed request leaves a JSON body here,
    # which would otherwise be quoted into the issue as the run's conclusion.
    case "$conclusion" in
        success | failure | cancelled | skipped | timed_out | action_required | neutral | stale) ;;
        *) conclusion="unknown" ;;
    esac

    # An issue number is always digits. Anything else is an API error body and must
    # not be handed to `gh issue edit` as though it were one.
    existing="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" \
        --state open --json number,title --limit 50 \
        --jq ".[] | select(.title == \"${TITLE}\") | .number" 2>/dev/null | head -1)"
    printf '%s' "$existing" | grep -qE '^[0-9]+$' || existing=""

    if [ -n "$existing" ]; then
        # Replace the body rather than append: see WHAT "ROLLING" MEANS HERE.
        if gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" --body-file "$BODY" \
            >/dev/null 2>&1; then
            echo "updated ci_failure_notice issue #${existing}"
        else
            fail "could not update issue #${existing}"
        fi
    else
        if gh issue create --repo "$GITHUB_REPOSITORY" --title "$TITLE" \
            --label "$LABEL" --body-file "$BODY" >/dev/null 2>&1; then
            echo "opened a new ci_failure_notice issue"
        else
            fail "could not create an issue (is the '${LABEL}' label missing, or issues: write not granted?)"
        fi
    fi
    ;;
passed)
    open="$(gh issue list --repo "$GITHUB_REPOSITORY" --label "$LABEL" \
        --state open --json number,title --limit 50 \
        --jq ".[] | select(.title == \"${TITLE}\") | .number" 2>/dev/null \
        | grep -E '^[0-9]+$' || true)"
    if [ -z "$open" ]; then
        echo "no open ci_failure_notice for ${workflow}"
    else
        while read -r num; do
            [ -n "$num" ] || continue
            if gh issue close "$num" --repo "$GITHUB_REPOSITORY" \
                --comment "${workflow} is green again. Closing." >/dev/null 2>&1; then
                echo "closed ci_failure_notice issue #${num}"
            else
                fail "could not close issue #${num}"
            fi
        done <<<"$open"
    fi
    ;;
esac

# The run log is the first place anyone looks, so the same record goes there.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ "$state" = failed ]; then
    {
        echo "### CI failure notice"
        echo ""
        cat "$BODY"
    } >>"$GITHUB_STEP_SUMMARY"
fi

exit 0