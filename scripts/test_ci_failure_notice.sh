#!/usr/bin/env bash
# Fixture for scripts/ci_failure_notice.sh.
#
# gh is stubbed, so this asserts the decision and the calls rather than creating
# anything. The cases that matter are the ones where doing the wrong thing is
# quiet:
#
#   - a repeat failure must UPDATE the existing issue, not open a second one.
#     Opening a new one per run turns a three-day outage into seventy-two issues,
#     which is the opposite of making the failure visible.
#   - a pass must CLOSE the record, or the repository keeps advertising a failure
#     that is no longer happening.
#   - a pass with nothing open must be a no-op, not an error.
#   - every exit is 0. A notification step that fails would replace a recorded
#     failure with a less informative one.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ci-notice.XXXXXX")"
export WORK
trap 'rm -rf "$WORK"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/ci_failure_notice.sh"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/gh-calls"
# Keep whatever was actually handed to --body-file. Recording that the call
# happened is not the same as recording its payload: the report was written to
# the wrong path once, every gh call still succeeded, and case 6 passed anyway
# because it only looked for the flag.
prev=""
for a in "$@"; do
    if [ "$prev" = "--body-file" ] && [ -f "$a" ]; then
        cp "$a" "$WORK/last-body"
    fi
    prev="$a"
done
case "$*" in
    # Order matters: the issue verbs are matched before anything generic, and the
    # run/jobs endpoints before the catch-all, or they fall through to a 404.
    *"issue list"*)
        cat "$WORK/open-issues" 2>/dev/null || true ;;
    *"issue create"*) echo "https://github.com/o/r/issues/1" ;;
    *"issue edit"*)   echo "https://github.com/o/r/issues/77" ;;
    *"issue close"*)  echo "https://github.com/o/r/issues/77" ;;
    *"check-runs/999/annotations"*)
        printf '```\nthird_party/BepInEx/foo.cs\nerror CS0103: the name x does not exist\n```\n' ;;
    *"actions/jobs/1000"*) echo "Create PR if changed" ;;
    *"actions/jobs/999"*)  echo "build" ;;
    *"actions/runs/"*"/jobs"*) printf 'job=build id=999\n' ;;
    *"actions/runs/"*)
        # the bare conclusion lookup
        echo "failure" ;;
    # gh writes the API error body to stdout when a request fails, and exits 1.
    # Modelling that as empty stdout is what let a JSON document through as if it
    # were an issue number on a real run.
    *) echo '{"message":"Not Found","status":"404"}'; exit 1 ;;
esac
STUB
chmod +x "$WORK/bin/gh"
# Case 4c replaces the stub with a minimal one to isolate the token argument, so
# keep a pristine copy: a case that reads what gh was handed needs the stub that
# records it, and reading a file left behind by an earlier case passes on
# someone else's bytes.
cp "$WORK/bin/gh" "$WORK/gh-full"

fails=0
calls() { grep -cF -- "$1" "$WORK/gh-calls" 2>/dev/null || true; }
grep_calls() { grep -F -- "$1" "$WORK/gh-calls" 2>/dev/null || true; }

run() { # run <workflow> <state> [open-issue-numbers-file-contents]
    : > "$WORK/gh-calls"
    # Absent, not truncated: an empty file and a missing one must both fail the
    # assertion below, so there is no way to read last run's report as this one's.
    rm -f "$WORK/last-body"
    printf '%s' "${3:-}" > "$WORK/open-issues"
    PATH="$WORK/bin:$PATH" \
    GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
    GITHUB_RUN_ID="12345" \
    GITHUB_REF_NAME="main" \
    GITHUB_SHA="abcdef1234567890abcdef1234567890abcdef12" \
    GITHUB_STEP_SUMMARY= \
    "$SCRIPT" "$1" "$2" 2>&1
    printf '%s' "$?" > "$WORK/rc"
}

expect() { # expect <want-rc> <label> <needle>
    local want="$1" label="$2" needle="$3"; shift 3
    local out rc
    out="$(run "$@")"; rc="$(cat "$WORK/rc")"
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1)); return
    fi
    if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
        echo "FAIL  $label: output did not mention '$needle'"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1)); return
    fi
    # Said out loud on success too. A test whose purpose is to prove something
    # must not pass silently, or a case that stopped exercising the path becomes
    # indistinguishable from one that still exercises it.
    echo "ok    $label"
}

echo "== ci_failure_notice.sh =="
echo

# 1. first failure, nothing open -> create
expect 0 "first failure opens an issue" "opened a new" \
    "sync-upstream" "failed" ""

# 1b. an issue list that returns an API error must not be handed to `gh issue
#     edit` as a number. gh prints the body on stdout and exits 1. The body is
#     passed as run's third argument, because run rewrites the file each time.
run "sync-upstream" "failed" '{"message":"Not Found","status":"404"}' >/dev/null
if grep -qF "issue edit" "$WORK/gh-calls" 2>/dev/null; then
    echo "FAIL  a 404 document was used as an issue number:"
    grep -F "issue edit" "$WORK/gh-calls" | sed 's/^/        /'
    fails=$((fails + 1))
else
    echo "ok    a 404 document is not used as an issue number"
fi

# 1c. a cancelled run must report the conclusion it has, not a JSON body
out="$(run "sync-upstream" "failed")"
if printf '%s' "$out" | grep -qF '{"message"' ; then
    echo "FAIL  an API error body leaked into the report:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
else
    echo "ok    no API error body reaches the report"
fi

# 2. repeat failure, one open -> update that one, never a second
expect 0 "repeat failure updates the same issue" "updated" \
    "sync-upstream" "failed" $'77\n'
if [ "$(calls 'issue create')" = 0 ]; then
    echo "ok    repeat failure does not open a second issue"
else
    echo "FAIL  repeat failure opened another issue:"; grep_calls "issue create" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 3. recovery closes the record
expect 0 "a pass closes the record" "closed" \
    "sync-upstream" "passed" $'77\n'

# 4. a pass with nothing open is a no-op, not an error
expect 0 "a pass with nothing open is a no-op" "no open" \
    "fetch-runtime" "passed" ""

# 4b. gh must receive a token. This script always exits 0, so a missing token
#     would otherwise make the notice step a silent no-op - the worst outcome for
#     a step whose entire job is to make failures visible. It must at least warn.
: > "$WORK/gh-calls"
out="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 \
        GITHUB_TOKEN="" GITHUB_STEP_SUMMARY= "$SCRIPT" sync-upstream failed 2>&1)"
if printf '%s' "$out" | grep -qF "no GH_TOKEN"; then
    echo "ok    a missing token is reported rather than silently ignored"
else
    echo "FAIL  no warning about the missing token:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 4c. and with a token, gh must actually receive it under the name it reads
: > "$WORK/gh-token"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf 'GH_TOKEN=%s\n' "${GH_TOKEN:-<unset>}" > "$WORK/gh-token"
case "$*" in
    *"issue list"*)  cat "$WORK/open-issues" 2>/dev/null || true ;;
    *"actions/runs/"*"/jobs"*) printf 'job=build id=999\n' ;;
    *) echo "" ;;
esac
STUB
chmod +x "$WORK/bin/gh"
printf '%s' "" > "$WORK/open-issues"
PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 \
    GITHUB_TOKEN="fixture-token" GITHUB_STEP_SUMMARY= "$SCRIPT" sync-upstream failed >/dev/null 2>&1
if [ "$(cat "$WORK/gh-token" 2>/dev/null)" = "GH_TOKEN=fixture-token" ]; then
    echo "ok    GITHUB_TOKEN is exported to gh as GH_TOKEN"
else
    echo "FAIL  gh saw: $(cat "$WORK/gh-token" 2>/dev/null || echo '<gh never ran>')"
    fails=$((fails + 1))
fi

# 5. exit status is always 0, even when the gh calls could not work, and even
#    when the context a caller might reasonably omit is missing
: > "$WORK/gh-calls"
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_TOKEN=t GITHUB_STEP_SUMMARY= "$SCRIPT" 2>/dev/null; echo $?)"
if [ "$rc" = 0 ]; then
    echo "ok    missing arguments still exit 0"
else
    echo "FAIL  missing arguments exited $rc"; fails=$((fails + 1))
fi

# The self-declared always-0 contract, checked with none of the optional context
# present. set -u turned a missing GITHUB_SHA into exit 1 before this existed.
: > "$WORK/gh-calls"
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_TOKEN=t \
      env -u GITHUB_RUN_ID -u GITHUB_REF_NAME -u GITHUB_SHA \
      GITHUB_STEP_SUMMARY= "$SCRIPT" sync-upstream failed >/dev/null 2>&1; echo $?)"
if [ "$rc" = 0 ]; then
    echo "ok    missing optional context still exits 0"
else
    echo "FAIL  missing GITHUB_RUN_ID/REF_NAME/SHA exited $rc"; fails=$((fails + 1))
fi

: > "$WORK/gh-calls"
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_TOKEN=t \
      GITHUB_STEP_SUMMARY= "$SCRIPT" wf bogus >/dev/null 2>&1; echo $?)"
if [ "$rc" = 0 ]; then
    echo "ok    an unknown state still exits 0"
else
    echo "FAIL  unknown state exited $rc"; fails=$((fails + 1))
fi

# 6. the body must carry the tool's own words, not just a link
#
# This case is titled that, and until now it only asserted that `--body-file`
# appeared in the call. The report was being written to `failure.body` in the
# repository root while --body-file received an empty mktemp file, so the issue
# opened with no body at all and this still passed. Assert the payload.
# The stub has to be the recording one: 4c left a minimal stub behind, and
# reading the file it did not write is how this case would pass on stale bytes.
cp "$WORK/gh-full" "$WORK/bin/gh"
# The report must not also be dropped in the working tree. It used to land in
# <repo>/failure.body, which is how a fake one got committed and tracked. Clear
# any stale copy first, so this asserts what *this* run wrote rather than what a
# run of an older version left behind.
rm -f "$REPO/failure.body"
run "sync-upstream" "failed" $'77\n' >/dev/null
if grep_calls "issue edit" >/dev/null; then
    echo "ok    body is written through issue edit"
else
    echo "FAIL  no issue edit call recorded"; fails=$((fails + 1))
fi
body="$(cat "$WORK/last-body" 2>/dev/null || true)"
if [ -z "$body" ]; then
    echo "FAIL  --body-file received an empty file"; fails=$((fails + 1))
elif [ "$(wc -c < "$WORK/last-body")" -lt 100 ]; then
    echo "FAIL  --body-file received only $(wc -c < "$WORK/last-body") bytes; that is not a report"
    fails=$((fails + 1))
else
    missing=""
    # Here-string, not `printf | grep -q`: grep -q exits on the first match and
    # closes the pipe, so under `pipefail` the status becomes printf's SIGPIPE
    # and a needle that IS present reads as absent - earlier in the body, which
    # is exactly where the run and commit links are. Same class as
    # test_sort_head_sigpipe.sh, which exists because a build decided itself by
    # this.
    for needle in "sync-upstream" "#12345" "main" "abcdef123456" "build" "error CS0103"; do
        grep -qF -- "$needle" <<<"$body" || missing="$missing '$needle'"
    done
    if [ -n "$missing" ]; then
        echo "FAIL  body is missing$missing"
        fails=$((fails + 1))
    else
        echo "ok    body carries the run, the job, and the tool's own error text"
    fi
fi
# The report must not also be dropped in the working tree. It used to land in
# <repo>/failure.body, which is how a fake one got committed and tracked.
if [ -e "$REPO/failure.body" ]; then
    echo "FAIL  the report was written to the repository root as failure.body"
    fails=$((fails + 1))
else
    echo "ok    nothing is written to the working tree"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"