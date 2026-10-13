#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - verify producer runs were built from this commit
# ==============================================================================
#
# WHY THIS EXISTS
#
# build-launcher-apk assembles three artifacts from three separate runs
# (fetch-runtime, build-native-deps, build-bepinex), each named by run id.
# A run id names a run, not its inputs: without this check the APK can be
# assembled from a runtime fetched on one commit, hook engines from another
# and a managed framework from a third, and every individual step still
# passes. verify_ci_artifacts.sh proves byte identity against each producer's
# own manifest; verify_attestation.sh proves origin. Neither proves the three
# runs agree on the commit. This one does.
#
# WHY IT FAILS CLOSED
#
# A missing gh, missing GITHUB_REPOSITORY, missing argument, or an API
# failure fails the build rather than skipping. "Cannot check" and "checked"
# are different states and only one of them may pass. A run whose head_sha
# cannot be read is reported as <unknown> and fails, so an API outage blocks
# the shipping path instead of opening it.
#
# USAGE
#   scripts/verify_producer_commit.sh <head-sha> <runtime-run-id> <native-run-id> <bepinex-run-id>
#
# Requires gh on PATH and GITHUB_REPOSITORY in the environment (gh reads
# GH_TOKEN / GITHUB_TOKEN for auth; this script does not take a token arg).
# ==============================================================================

set -uo pipefail

if [ "$#" -ne 4 ]; then
    echo "verify_producer_commit: expected 4 arguments: <head-sha> <runtime-run-id> <native-run-id> <bepinex-run-id>" >&2
    echo "  got $#: $*" >&2
    exit 2
fi

HEAD_SHA="$1"
RUNTIME_RUN_ID="$2"
NATIVE_RUN_ID="$3"
BEPINEX_RUN_ID="$4"

if [ -z "$HEAD_SHA" ]; then
    echo "verify_producer_commit: head-sha is empty." >&2
    exit 2
fi

REPO_NAME="${GITHUB_REPOSITORY:-}"
if [ -z "$REPO_NAME" ]; then
    echo "verify_producer_commit: GITHUB_REPOSITORY is required." >&2
    exit 2
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "verify_producer_commit: gh is not on PATH; cannot verify producer commits." >&2
    exit 2
fi

for rid in "$RUNTIME_RUN_ID" "$NATIVE_RUN_ID" "$BEPINEX_RUN_ID"; do
    if [ -z "$rid" ]; then
        echo "::error::verify_producer_commit: a producer run id is empty; refusing to assemble from an unnamed run."
        exit 2
    fi
done

echo "============================================================"
echo " Producer commit verification                             "
echo "============================================================"
echo "  repository : $REPO_NAME"
echo "  assembling from commit $HEAD_SHA"
echo ""

fails=0

check_one() { # check_one <run-id> <label>
    local rid="$1" label="$2" sha
    sha="$(gh api "repos/$REPO_NAME/actions/runs/$rid" --jq '.head_sha' 2>/dev/null || echo "<unknown>")"
    # An empty response is the same as no response: the API did not answer
    # what this run was built from, so there is nothing to compare.
    if [ -z "$sha" ]; then
        sha="<unknown>"
    fi
    echo "  run $rid ($label) -> $sha"
    if [ "$sha" = "$HEAD_SHA" ]; then
        printf '  [OK]     %s: run %s was built from this commit\n' "$label" "$rid"
    else
        echo "::error::run $rid ($label) was built from $sha, not $HEAD_SHA."
        echo "Re-run that workflow on this commit, or pass a matching run id."
        fails=$((fails + 1))
    fi
}

check_one "$RUNTIME_RUN_ID" "fetch-runtime"
check_one "$NATIVE_RUN_ID" "build-native-deps"
check_one "$BEPINEX_RUN_ID" "build-bepinex"

echo ""
if [ "$fails" -eq 0 ]; then
    echo "RESULT: all three producer runs were built from this commit."
    exit 0
fi

echo "RESULT: FAILED - $fails producer run(s) were built from a different commit (or could not be read)."
exit 1
