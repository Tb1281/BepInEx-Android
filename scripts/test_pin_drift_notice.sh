#!/usr/bin/env bash
# Fixture for scripts/pin_drift_notice.sh.
#
# gh is stubbed, so this asserts the decision and the calls rather than creating
# anything. The cases that matter are the ones where doing the wrong thing is
# quiet:
#
#   - first drift must OPEN one issue; repeat drift must UPDATE it, never open
#     a second one per run.
#   - current must CLOSE the record; current with nothing open is a no-op.
#   - the body must carry the drift report handed in, not just a link (the
#     ci_failure_notice predecessor once shipped empty bodies while passing).
#   - every exit is 0.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/drift-notice.XXXXXX")"
export WORK
trap 'rm -rf "$WORK"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/pin_drift_notice.sh"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/gh-calls"
prev=""
for a in "$@"; do
    if [ "$prev" = "--body-file" ] && [ -f "$a" ]; then
        cp "$a" "$WORK/last-body"
    fi
    prev="$a"
done
case "$*" in
    *"issue list"*)
        cat "$WORK/open-issues" 2>/dev/null || true ;;
    *"issue create"*) echo "https://github.com/o/r/issues/1" ;;
    *"issue edit"*)   echo "https://github.com/o/r/issues/77" ;;
    *"issue close"*)  echo "https://github.com/o/r/issues/77" ;;
    *) echo '{"message":"Not Found","status":"404"}'; exit 1 ;;
esac
STUB
chmod +x "$WORK/bin/gh"

fails=0
calls() { grep -cF -- "$1" "$WORK/gh-calls" 2>/dev/null || true; }

run() { # run <state> [report-contents] [open-issues-contents]
    : > "$WORK/gh-calls"
    rm -f "$WORK/last-body"
    if [ $# -ge 2 ]; then
        printf '%s' "$2" > "$WORK/report.txt"
        report="$WORK/report.txt"
    else
        report=""
    fi
    printf '%s' "${3:-}" > "$WORK/open-issues"
    PATH="$WORK/bin:$PATH" \
    GITHUB_REPOSITORY="o/r" \
    GITHUB_RUN_ID="12345" \
    GITHUB_REF_NAME="main" \
    GITHUB_SHA="abcdef1234567890abcdef1234567890abcdef12" \
    "$SCRIPT" "$1" "$report" 2>&1
    printf '%s' "$?" > "$WORK/rc"
}

expect() { # expect <want-rc> <label> <needle> <state> [report] [open]
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
    echo "ok    $label"
}

REPORT=$'  MOVED    MonoMod  pin abcdef1234 -> upstream 567890abcd\n RESULT: 4 current, 1 moved, 0 unknown'

echo "== pin_drift_notice.sh =="
echo

# 1. first drift, nothing open -> create
expect 0 "first drift opens an issue" "opened a new" \
    "drifted" "$REPORT" ""

# 1b. an API error body must not be handed to `gh issue edit` as a number
run "drifted" "$REPORT" '{"message":"Not Found","status":"404"}' >/dev/null
if grep -qF "issue edit" "$WORK/gh-calls" 2>/dev/null; then
    echo "FAIL  a 404 document was used as an issue number"
    fails=$((fails + 1))
else
    echo "ok    a 404 document is not used as an issue number"
fi

# 2. repeat drift updates the same issue, never a second
expect 0 "repeat drift updates the same issue" "updated" \
    "drifted" "$REPORT" $'77\n'
if [ "$(calls 'issue create')" = 0 ]; then
    echo "ok    repeat drift does not open a second issue"
else
    echo "FAIL  repeat drift opened another issue"; fails=$((fails + 1))
fi

# 3. current closes the record
expect 0 "current closes the record" "closed" \
    "current" "" $'77\n'

# 4. current with nothing open is a no-op
expect 0 "current with nothing open is a no-op" "no open" \
    "current" "" ""

# 5. exit status is always 0, even with missing/unknown state
: > "$WORK/gh-calls"
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_TOKEN=t "$SCRIPT" 2>/dev/null; echo $?)"
[ "$rc" = 0 ] && echo "ok    missing arguments still exit 0" \
    || { echo "FAIL  missing arguments exited $rc"; fails=$((fails + 1)); }
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_TOKEN=t "$SCRIPT" bogus /nonexistent 2>/dev/null; echo $?)"
[ "$rc" = 0 ] && echo "ok    an unknown state still exits 0" \
    || { echo "FAIL  unknown state exited $rc"; fails=$((fails + 1)); }

# 5b. missing token warns rather than silently no-ops
printf '%s' "drift report" > "$WORK/report.txt"
out="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 \
    GITHUB_TOKEN="" "$SCRIPT" drifted "$WORK/report.txt" 2>&1)"
if printf '%s' "$out" | grep -qF "no GH_TOKEN"; then
    echo "ok    a missing token is reported rather than silently ignored"
else
    echo "FAIL  no warning about the missing token:"; printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 6. the body must carry the drift report, and nothing lands in the tree
rm -f "$REPO/drift.body"
run "drifted" "$REPORT" $'77\n' >/dev/null
body="$(cat "$WORK/last-body" 2>/dev/null || true)"
if [ -z "$body" ]; then
    echo "FAIL  --body-file received an empty file"; fails=$((fails + 1))
else
    missing=""
    for needle in "MonoMod" "abcdef1234" "#12345" "provenance/*.env"; do
        grep -qF -- "$needle" <<<"$body" || missing="$missing '$needle'"
    done
    if [ -n "$missing" ]; then
        echo "FAIL  body is missing$missing"; fails=$((fails + 1))
    else
        echo "ok    body carries the drift report and the run link"
    fi
fi
if [ -e "$REPO/drift.body" ]; then
    echo "FAIL  the report was written to the repository root"
    fails=$((fails + 1))
else
    echo "ok    nothing is written to the working tree"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
