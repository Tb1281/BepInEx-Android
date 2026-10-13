#!/usr/bin/env bash
# Fixture harness for scripts/verify_producer_commit.sh.
#
# This gate exists to stop an APK assembled from three commits at once, so the
# cases that matter are the quiet ones. `gh` is stubbed, which lets this assert
# the decision without touching the Actions API:
#
#   - three runs on this commit PASS.
#   - ONE run from another commit FAILS, and names the run id. A gate that
#     passed because "most runs were fine" is the shape this repository has
#     removed three times.
#   - an API failure (gh exits non-zero) FAILS as <unknown>, not as a skip.
#   - an empty head_sha from the API FAILS as <unknown>.
#   - a missing gh FAILS rather than skipping. "Cannot check" and "checked"
#     are different states and only one of them may pass.
#   - missing GITHUB_REPOSITORY FAILS rather than querying the wrong repo.
#   - missing arguments FAIL rather than comparing against nothing.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/producer-commit.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/verify_producer_commit.sh"
HEAD="abcdef1234567890abcdef1234567890abcdef12"
OTHER="1111111111111111111111111111111111111111"

mkdir -p "$FIX/bin"

# Decision table: one "<run-id> <sha>" per line. A run id with no line makes
# the stub fail like a real API outage. The word EMPTY means "return empty".
: > "$FIX/runs"

cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
# Only the `gh api repos/.../actions/runs/<id> --jq .head_sha` shape is served.
[ "${1:-}" = "api" ] || exit 1
path="$2"
rid="${path##*/runs/}"
rid="${rid%%/*}"
line="$(awk -v r="$rid" '$1 == r { print $2 }' "$WORK/runs")"
if [ -z "$line" ]; then
    echo "Not Found" >&2
    exit 1
fi
if [ "$line" = "EMPTY" ]; then
    printf ''
    exit 0
fi
printf '%s\n' "$line"
STUB
chmod +x "$FIX/bin/gh"

export WORK="$FIX"

fails=0

run_gate() { # run_gate <head> <r1> <r2> <r3> -> sets FIX/rc and FIX/out
    PATH="$FIX/bin:$PATH" \
    GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
    "$SCRIPT" "$1" "$2" "$3" "$4" >"$FIX/out" 2>&1
    printf '%s' "$?" > "$FIX/rc"
}

check_rc() { # check_rc <want> <label>
    local want="$1" label="$2" rc
    rc="$(cat "$FIX/rc")"
    if [ "$rc" = "$want" ]; then
        echo "ok    $label"
    else
        echo "FAIL  $label: expected exit $want, got $rc"
        cat "$FIX/out" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
}

check_out() { # check_out <label> <needle>
    local label="$1" needle="$2"
    if grep -qF -- "$needle" "$FIX/out"; then
        echo "ok    $label"
    else
        echo "FAIL  $label: output did not mention '$needle'"
        cat "$FIX/out" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
}

echo "== verify_producer_commit.sh =="

# 1. three runs on this commit pass
printf '101 %s\n102 %s\n103 %s\n' "$HEAD" "$HEAD" "$HEAD" > "$FIX/runs"
run_gate "$HEAD" 101 102 103
check_rc 0 "three runs on this commit pass"
check_out "and reports the verdict" "all three producer runs were built from this commit"

# 2. ONE run from another commit fails, and names the run id
printf '101 %s\n102 %s\n103 %s\n' "$HEAD" "$OTHER" "$HEAD" > "$FIX/runs"
run_gate "$HEAD" 101 102 103
check_rc 1 "one run from another commit fails"
check_out "and names the run id" "102"
check_out "and names the expected commit" "$HEAD"

# 3. an API failure fails as <unknown>, not as a skip
printf '101 %s\n103 %s\n' "$HEAD" "$HEAD" > "$FIX/runs"
run_gate "$HEAD" 101 102 103
check_rc 1 "an API failure fails the gate"
check_out "and reports unknown" "<unknown>"

# 4. an empty head_sha from the API fails as <unknown>
printf '101 %s\n102 EMPTY\n103 %s\n' "$HEAD" "$HEAD" > "$FIX/runs"
run_gate "$HEAD" 101 102 103
check_rc 1 "an empty head_sha fails the gate"
check_out "and reports unknown" "<unknown>"

# 5. a missing gh must fail, not skip
printf '101 %s\n102 %s\n103 %s\n' "$HEAD" "$HEAD" "$HEAD" > "$FIX/runs"
mkdir -p "$FIX/nogh"
for t in bash sh basename cat grep awk printf sed env; do
    p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$FIX/nogh/$t"
done
rc="$(PATH="$FIX/nogh" GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
      "$SCRIPT" "$HEAD" 101 102 103 >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    a missing gh is an error, not a skip"
else
    echo "FAIL  a missing gh exited 0"; fails=$((fails + 1))
fi

# 6. missing repository context must fail
rc="$(env -u GITHUB_REPOSITORY PATH="$FIX/bin:$PATH" \
      "$SCRIPT" "$HEAD" 101 102 103 >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    missing repository context is an error"
else
    echo "FAIL  missing context exited 0"; fails=$((fails + 1))
fi

# 7. missing arguments must fail rather than compare against nothing
rc="$(PATH="$FIX/bin:$PATH" GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
      "$SCRIPT" "$HEAD" 101 102 >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    missing arguments are an error"
else
    echo "FAIL  missing arguments exited 0"; fails=$((fails + 1))
fi

# 8. an empty run id must fail rather than query runs/ with no id
rc="$(PATH="$FIX/bin:$PATH" GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
      "$SCRIPT" "$HEAD" 101 "" 103 >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    an empty run id is an error"
else
    echo "FAIL  an empty run id exited 0"; fails=$((fails + 1))
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
