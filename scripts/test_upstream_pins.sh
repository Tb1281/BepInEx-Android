#!/usr/bin/env bash
# Fixture for scripts/check_upstream_pins.sh.
#
# The comparison logic is tested against real local git remotes (file://),
# not a stub of git: the stub cannot know how `git ls-remote` actually fails,
# and ls-remote's failure shape - exit code, empty stdout on a missing ref,
# empty stdout on an unreachable one - is exactly a half of the behaviour
# being pinned here.
#
# States pinned:
#   pin at upstream tip          -> current, exit 0
#   upstream moved               -> MOVED, exit 0 (news, not a failure)
#   url unreachable              -> unknown, exit 0, claims nothing
#   ref does not exist           -> unknown, exit 0, claims nothing
#   malformed pin line           -> exit 1
#   --print-table derives the real table from provenance/*.env without a network
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pins.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/check_upstream_pins.sh"

fails=0
check() { # check <want-rc> <label> <needle...>   (PINS_FILE must be set)
    local want="$1" label="$2"; shift 2
    local out rc needle ok=1
    # GITHUB_STEP_SUMMARY must not leak into the real CI summary: the fixture
    # runs the script repeatedly with fake pins, and each run would otherwise
    # append its report to the job summary (the duplicated "upstream pin
    # drift" sections seen on the Actions page).
    out="$(GITHUB_STEP_SUMMARY= PINS_FILE="$WORK/pins.txt" "$SCRIPT" 2>&1)"; rc=$?
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

echo "== check_upstream_pins.sh =="
echo

# A real upstream with two commits; the pin will be the first, then the tree
# moves and the second run must say MOVED.
git init -q "$WORK/up"
git -C "$WORK/up" -c user.email=t@t -c user.name=t commit -q --allow-empty -m one
FIRST="$(git -C "$WORK/up" rev-parse HEAD)"
git -C "$WORK/up" -c user.email=t@t -c user.name=t commit -q --allow-empty -m two
SECOND="$(git -C "$WORK/up" rev-parse HEAD)"

# 1. pin matches upstream tip
printf 'demo|%s|master|%s\n' "$WORK/up" "$SECOND" > "$WORK/pins.txt"
check 0 "pin at upstream tip reports current" "current  demo" "1 current"

# 2. upstream moved since the pin
printf 'demo|%s|master|%s\n' "$WORK/up" "$FIRST" > "$WORK/pins.txt"
check 0 "moved upstream is news, not a failure" "MOVED    demo" "1 moved" \
    "nothing here" "was bumped"

# 3. url unreachable must not be reported as current
printf 'demo|%s/does-not-exist|master|%s\n' "$WORK" "$FIRST" > "$WORK/pins.txt"
check 0 "unreachable upstream claims nothing" "UNKNOWN  demo" "1 unknown"

# 4. ref that does not exist
printf 'demo|%s|no-such-branch|%s\n' "$WORK/up" "$FIRST" > "$WORK/pins.txt"
check 0 "missing ref claims nothing" "UNKNOWN  demo" "1 unknown"

# 5. malformed line is a script defect, not a drift reading
printf 'demo|%s|master\n' "$WORK/up" > "$WORK/pins.txt"
check 1 "malformed pin line fails" "malformed pin line"

# 6. the real derivation path: env sourcing for all five pins - without a
#    network.
#
#    The names come from the pin file's own basename, so they are lower case.
#    These hardcoded names are what detected the half-finished submodule
#    migration (docs/todo.md Phase 3): while check_upstream_pins.sh still read
#    gitlinks it emitted "BepInEx"/"Pine" and this case failed, which is the
#    whole reason the fixture runs against the real tree rather than a stub.
out="$(GITHUB_STEP_SUMMARY= "$SCRIPT" --print-table 2>&1)"; rc=$?
ok=1
[ "$rc" -eq 0 ] || ok=0
for name in bepinex lsplant dobby monomod il2cppinterop; do
    printf '%s' "$out" | grep -q "^${name}|" || ok=0
done
printf '%s' "$out" | grep -q 'provenance' || true
if [ "$ok" = 1 ] && printf '%s' "$out" | grep -qE '^[A-Za-z]+\|https?://[^|]+\|[^|]+\|[0-9a-f]{40}$'; then
    echo "ok    --print-table derives name|url|ref|sha for every pin"
else
    echo "FAIL  --print-table derivation"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 6b. The exact pin set, no more and no less. Deriving these from a glob over
#     provenance/*.env would pick up an unrelated file there, and would drop a
#     renamed pin file without complaint; either would leave this passing while
#     the report watched something else. Assert the set literally.
expected_pins="bepinex lsplant dobby monomod il2cppinterop"
actual_pins="$(printf '%s' "$out" | awk -F'|' '/\|/ {print $1}' | tr '\n' ' ' | sed 's/ *$//')"
if [ "$actual_pins" = "$expected_pins" ]; then
    echo "ok    all five pins derived, none extra and none missing"
else
    echo "FAIL  pin set is '$actual_pins', expected '$expected_pins'"
    fails=$((fails + 1))
fi

# 6c. No pin file may sit on disk unwatched. dotnet-runtime.env is deliberately
#     exempt: it has its own checker with a stricter contract, and duplicating it
#     here would make two scripts that can disagree.
for f in "$REPO"/provenance/*.env; do
    case "$(basename "$f")" in
        dotnet-runtime.env) continue ;;
    esac
    base="$(basename "$f" .env)"
    if printf '%s' "$out" | grep -q "^${base}|"; then
        echo "ok    $(basename "$f") is watched"
    else
        echo "FAIL  $(basename "$f") exists but no pin is derived from it"
        fails=$((fails + 1))
    fi
done

# 6d. The gitlink cross-check must still run even though the submodules are
#     removed. It is skipped under PINS_FILE (the fixtures above override the
#     table with throwaway local remotes), so the real tree is the only thing
#     that can exercise the index-based half-removed-submodule refusal.
cross="$(GITHUB_STEP_SUMMARY= "$SCRIPT" 2>&1 )"; cross_rc=$?
if printf '%s' "$cross" | grep -q "gitlink agrees"; then
    echo "ok    gitlink/env cross-check runs and agrees"
elif printf '%s' "$cross" | grep -q "no gitlinks"; then
    echo "ok    gitlinks removed; cross-check reports it checks nothing"
elif printf '%s' "$cross" | grep -q "disagrees with provenance"; then
    # Report the defect itself rather than only the fixture's summary of it: a
    # disagreement is the exact fault the migration window can produce, and
    # "cross-check failed" alone would not tell a reader which pin moved.
    echo "FAIL  gitlink and pin file disagree:"
    printf '%s\n' "$cross" | grep "::error::" | sed 's/^/        /'
    fails=$((fails + 1))
else
    echo "FAIL  cross-check neither agreed nor reported its absence (exit $cross_rc)"
    printf '%s\n' "$cross" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# 6e. The submodules are removed now, so the cross-check has nothing to compare.
#     It must say so, and it must refuse a half-removed submodule rather than
#     reporting success: a gitlink with no .gitmodules (or the reverse) pins
#     nothing, and "silently agreed" is the failure this fixture exists to stop.
#
#     The mutation is applied to a copy of the index via GIT_INDEX_FILE, so the
#     real index is never touched - this is the one check that would otherwise
#     require moving submodule directories around to test. Ask git for the
#     index path instead of assuming $REPO/.git/index: in a linked worktree,
#     .git is a file and the real index lives under the main repository's
#     .git/worktrees/<name>/ directory.
HALF="$(mktemp -d "${TMPDIR:-/tmp}/halfsub.XXXXXX")"
INDEX_FILE="$(cd "$REPO" && git rev-parse --path-format=absolute --git-path index)"
if ! cp "$INDEX_FILE" "$HALF/index"; then
    echo "FAIL  could not copy the index from $INDEX_FILE"
    fails=$((fails + 1))
fi

run_with_index() { # run_with_index <index-file>
    ( cd "$REPO" && GITHUB_STEP_SUMMARY= GIT_INDEX_FILE="$1" "$SCRIPT" 2>&1 )
}

# Baseline: a pristine copy of the index must report no gitlinks.
run_with_index "$HALF/index" | grep -q "no gitlinks" \
    && echo "ok    pristine index: no gitlinks, cross-check reports it" \
    || { echo "FAIL  pristine index did not report 'no gitlinks'"; fails=$((fails + 1)); }

# gitlink indexed with no .gitmodules -> must refuse.
# Derive the gitlink from the live provenance pin: a hard-coded SHA turns this
# into a pin-disagreement test whenever BepInEx moves, hiding the half-removed
# submodule refusal this case exists to exercise.
BEPINEX_PIN="$(sed -n 's/^UPSTREAM_COMMIT=//p' "$REPO/provenance/bepinex.env")"
[ -n "$BEPINEX_PIN" ] || { echo "FAIL  could not read BepInEx pin"; fails=$((fails + 1)); }
GIT_INDEX_FILE="$HALF/index" git update-index --add \
    --cacheinfo 160000,"$BEPINEX_PIN",third_party/BepInEx 2>/dev/null
# Deliberately not $out: that name holds the --print-table result that case 7
# still needs, and overwriting it here made a passing case fail two steps later.
half_out="$(run_with_index "$HALF/index")"
if printf '%s' "$half_out" | grep -q "still indexed but .gitmodules is gone"; then
    echo "ok    refuses a gitlink with no .gitmodules"
else
    echo "FAIL  gitlink without .gitmodules was not refused:"
    printf '%s\n' "$half_out" | sed 's/^/        /'
    fails=$((fails + 1))
fi
rm -rf "$HALF"

# 7. derived MonoMod ref is the one provenance/monomod.env declares, and it
#    must not be master - the 2026-10-08 finding is recorded in the env file.
printf '%s' "$out" | grep -q '^monomod|[^|]*|reorganize|' \
    && echo "ok    monomod tracks reorganize, not master" \
    || { echo "FAIL  monomod ref is not reorganize"; fails=$((fails + 1)); }

echo
if [ "$fails" -eq 0 ]; then
    echo "pass"
else
    echo "$fails failure(s)"
    exit 1
fi
