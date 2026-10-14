#!/usr/bin/env bash
# Fixture for scripts/mark_verified.sh.
#
# The script's job is a decision plus a write, and the decision is the part that
# can be wrong quietly. These cases are the guards that must refuse, plus the two
# distinct write endpoints - creating a ref and force-moving one are different
# calls, and using the wrong one either fails on the first run or leaves a marker
# that cannot be advanced.
#
# gh is stubbed rather than called. The stub returns a *real* commit for the
# existing tag, because the script checks that the commit it is about to tag is
# present in the checkout, and a made-up SHA would be rejected by that guard
# before the case under test was ever reached. An earlier version of this fixture
# returned a fake SHA and three cases failed for reasons that had nothing to do
# with what they were testing.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mark-verified.XXXXXX")"
export WORK                      # the harness runs a fresh bash per case
trap 'rm -rf "$WORK"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/mark_verified.sh"

HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
# CI checks out shallow (fetch-depth 1), so HEAD~1 does not exist there.
# EXISTING_SHA is only ever compared as a string by mark_verified.sh (the
# presence guard applies to the target, not to the stored value), so fall
# back to a distinct well-formed hex that still exercises the MOVE branch.
PREV_SHA="$(git -C "$REPO" rev-parse HEAD~1 2>/dev/null || echo 1111111111111111111111111111111111111111)"
FAKE_SHA="0000000000000000000000000000000000000000"

mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/gh-calls"
# Record what gh actually sees. A stub that ignores credentials cannot catch the
# thing that broke this script on its first real run: gh reads neither GH_TOKEN nor
# GITHUB_TOKEN on its own, so every call failed with exit 4 until the workflow
# passed the token in.
printf 'GH_TOKEN=%s\n' "${GH_TOKEN:-<unset>}" > "$WORK/gh-token"
case "$*" in
    *"-X POST"*| *"-X PATCH"*)
        echo "refs/tags/verified"
        ;;
    *)
        # The GET of the existing ref. When it does not exist, this reproduces what
        # gh actually does - the API error body on STDOUT with a non-zero exit,
        # not an empty stdout. Modelling it as empty stdout is what let a JSON
        # document reach the script as if it were a commit id on the real run.
        if [ "${TAG_EXISTS:-0}" != 1 ]; then
            echo '{"message":"Not Found","documentation_url":"https://github.com","status":"404"}'
            exit 1
        fi
        echo "$EXISTING_SHA"
        ;;
esac
STUB
chmod +x "$WORK/bin/gh"

fails=0

# run <ref> <sha> <tag-exists:0|1> <existing-sha> [extra-args...]
# An array rather than ${5:-}, so an absent fifth argument means no argument
# rather than an empty one - the script rejects an unknown option, and "" is one.
run() {
    : > "$WORK/gh-calls"
    rm -f "$WORK/gh-token"
    local -a extra=()
    [ "$#" -ge 5 ] && extra=("$5")
    PATH="$WORK/bin:$PATH" \
    GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
    GITHUB_REF="$1" GITHUB_SHA="$2" TAG_EXISTS="$3" EXISTING_SHA="$4" \
    GITHUB_TOKEN="fixture-token" \
    "$SCRIPT" "${extra[@]}" 2>&1
}

expect() { # expect <want-rc> <label> <needle> -- args passed to run
    local want="$1" label="$2" needle="$3"; shift 3
    local out rc
    out="$(run "$@")"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1)); return
    fi
    if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
        echo "FAIL  $label: output did not mention '$needle'"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
}

# Count lines in the call log matching a literal needle. Takes the needle as $1,
# so callers must not pass an argument separator before it.
calls() { grep -cF -- "$1" "$WORK/gh-calls" 2>/dev/null || true; }

echo "== mark_verified.sh =="
echo "   HEAD ${HEAD_SHA:0:12}   HEAD~1 ${PREV_SHA:0:12}"
echo

# 0. gh must receive a token. This is the case a stub that ignores credentials
#    cannot catch, and it is the one that broke on the first real run: gh does
#    not read GITHUB_TOKEN, so the write failed with exit 4 and a help message
#    about GH_TOKEN after the step had already printed "[NEW]".
run "refs/heads/main" "$HEAD_SHA" 0 "$PREV_SHA" >/dev/null
if [ "$(cat "$WORK/gh-token" 2>/dev/null)" = "GH_TOKEN=fixture-token" ]; then
    echo "ok    GITHUB_TOKEN is exported to gh as GH_TOKEN"
else
    echo "FAIL  gh saw: $(cat "$WORK/gh-token" 2>/dev/null || echo '<gh never ran>')"
    fails=$((fails + 1))
fi

# 0b. and it must refuse rather than pretend when there is no token at all
rc="$(PATH="$WORK/bin:$PATH" GITHUB_REPOSITORY=o/r GITHUB_REF=refs/heads/main \
      GITHUB_SHA="$HEAD_SHA" GITHUB_TOKEN="" "$SCRIPT" >/dev/null 2>&1; echo $?)"
if [ "$rc" != 0 ]; then
    echo "ok    no token at all is a failure, not a silent no-op"
else
    echo "FAIL  ran with no token and exited 0"
    fails=$((fails + 1))
fi

# 1. default branch, no tag yet -> create via POST.
#    The stub returns the API error body on stdout here, exactly as gh does, so
#    this case also proves the script does not mistake a 404 document for a commit.
expect 0 "default branch creates the tag" "[NEW]" \
    "refs/heads/main" "$HEAD_SHA" 0 "$PREV_SHA"
if [ "$(calls '-X POST')" = 1 ] && [ "$(calls '-X PATCH')" = 0 ]; then
    echo "ok    creation uses POST /git/refs only"
else
    echo "FAIL  wrong endpoint on creation:"; sed 's/^/        /' "$WORK/gh-calls"
    fails=$((fails + 1))
fi

# 1b. the 404 document must never be mistaken for a commit id. gh writes the API
#     error body to stdout and exits 1, so `|| true` alone leaves the JSON behind.
out="$(run "refs/heads/main" "$HEAD_SHA" 0 "$PREV_SHA")"
if printf '%s' "$out" | grep -qF '{"message"' ; then
    echo "FAIL  the 404 document leaked into the report:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
else
    echo "ok    a 404 response is not reported as a commit id"
fi

# 2. default branch, tag elsewhere -> force-move via PATCH
expect 0 "default branch moves an existing tag" "[MOVE]" \
    "refs/heads/main" "$HEAD_SHA" 1 "$PREV_SHA"
if [ "$(calls '-X PATCH')" = 1 ] && [ "$(calls 'force=true')" = 1 ]; then
    echo "ok    advance uses PATCH with force=true"
else
    echo "FAIL  advance did not force-move:"; sed 's/^/        /' "$WORK/gh-calls"
    fails=$((fails + 1))
fi

# 3. tag already at this commit -> no write
expect 0 "already-current is a no-op" "[NO-OP]" \
    "refs/heads/main" "$HEAD_SHA" 1 "$HEAD_SHA"
if [ "$(calls '-X POST')" = 0 ] && [ "$(calls '-X PATCH')" = 0 ]; then
    echo "ok    no-op writes nothing"
else
    echo "FAIL  no-op still wrote:"; sed 's/^/        /' "$WORK/gh-calls"
    fails=$((fails + 1))
fi

# 4. feature branch refuses, and touches nothing
expect 0 "feature branch refuses to mark" "[SKIP]" \
    "refs/heads/some-feature" "$HEAD_SHA" 0 "$PREV_SHA"
if [ "$(calls '-X POST')" = 0 ] && [ "$(calls '-X PATCH')" = 0 ]; then
    echo "ok    feature branch writes nothing"
else
    echo "FAIL  feature branch wrote:"; sed 's/^/        /' "$WORK/gh-calls"
    fails=$((fails + 1))
fi

# 5. a pull_request ref is not the default branch either
expect 0 "pull-request ref refuses to mark" "[SKIP]" \
    "refs/pull/42/merge" "$HEAD_SHA" 0 "$PREV_SHA"

# 6. an unreachable sha fails loudly rather than leaving a tag pointing at nothing
expect 1 "unknown sha fails rather than tagging" "is not a commit in this checkout" \
    "refs/heads/main" "$FAKE_SHA" 0 "$PREV_SHA"
if [ "$(calls '-X POST')" = 0 ] && [ "$(calls '-X PATCH')" = 0 ]; then
    echo "ok    refused sha wrote nothing"
else
    echo "FAIL  refused sha still wrote:"; sed 's/^/        /' "$WORK/gh-calls"
    fails=$((fails + 1))
fi

# 7. dry-run decides, reads, and writes nothing. The read is expected - it is how
#    the decision is made - so the assertion is on the write calls specifically.
out="$(run "refs/heads/main" "$HEAD_SHA" 0 "$PREV_SHA" --dry-run)"
if printf '%s' "$out" | grep -qF "[DRY-RUN]" \
   && [ "$(calls '-X POST')" = 0 ] && [ "$(calls '-X PATCH')" = 0 ]; then
    echo "ok    dry-run decides and writes nothing"
else
    echo "FAIL  dry-run was not inert:"; printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"