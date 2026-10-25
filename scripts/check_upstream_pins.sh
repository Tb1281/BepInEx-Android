#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - report drift of every upstream pin in one place
# ==============================================================================
#
# WHAT THIS IS FOR
#
# Until now only one of our pins was watched. BepInEx moved because
# sync-upstream.yml asks about it every six hours, and the .NET runtime moved
# because scripts/check_runtime_pin.sh asks Microsoft once a week. MonoMod's
# pin went 131 commits behind origin/reorganize while nothing anywhere asked
# the question - the drift was found by hand, not by a job. Il2CppInterop and
# dobby were in the same unwatched state; it is luck, not a system, that they
# happen to be in sync today.
#
# This script asks the question for every pin this repository consumes and
# prints a report. It runs inside sync-upstream.yml, which is already
# scheduled and already has the failure-notice step attached (rule 3 in
# docs/todo.md: a new scheduled run gets an alert step).
#
# WHY IT REPORTS AND DOES NOT BUMP
#
# Rule 1 of docs/todo.md: pins are moved by a human. A stale runtime pin
# SIGABRTs on the device, a stale MonoMod pin fails every Harmony patch on
# the device, and a stale Cpp2IL pin changes interop output - none of that
# can be detected from CI alone, so none of it can be automated either.
# Bumping is news followed by a person; this script only produces the news.
#
# WHY A PIN THAT CANNOT BE CHECKED IS NOT "CURRENT"
#
# Rule 4 corollary in check_runtime_pin.sh: a check that cannot run must
# not report "current". If the upstream is unreachable, or the recorded ref
# no longer exists, the report says currency unknown and claims nothing.
# Exit status follows the same rule: an unreachable upstream is news, an
# unreadable pin source is a script defect and exits 1.
#
# THE PIN SOURCES
#
# There is one shape of pin in this repository:
#
#   provenance/*.env      BepInEx, LSPlant, dobby, MonoMod, Il2CppInterop (and
#                         the runtime, watched separately by
#                         scripts/check_runtime_pin.sh)
#
# Each file declares the same three variables - UPSTREAM_URL, UPSTREAM_REF,
# UPSTREAM_COMMIT - so this script has exactly one input shape to get right. It
# used to have two: BepInEx, Pine and dobby came from submodule gitlinks, parsed
# out of .gitmodules and git ls-tree. Those gitlinks are gone
# (docs/todo.md, Phase 3 task 11). check_gitlink_agreement below still runs and
# still refuses a half-removed submodule - see its comment for why that check was
# not simply deleted along with the thing it was checking.
#
# The runtime is deliberately not repeated here: it has its own checker with
# a stricter contract (the version must also line up with the PAL Java
# contract), and duplicating it here would make two scripts that can disagree.
#
# USAGE
#   scripts/check_upstream_pins.sh [--print-table]
#
#   --print-table   derive the pin table from provenance/*.env and print it
#                   without touching the network. This is the half of the
#                   script that cannot be stubbed: it exercises the env
#                   sourcing and the missing-variable refusals, so its fixture
#                   runs it for real.
#
# Environment:
#   PINS_FILE        override the derived pin table (tests). Lines:
#                   name|url|ref|pinned_sha , one per line, '#' comments.
#   GITHUB_STEP_SUMMARY  when set, the report is also appended here.
# ==============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PRINT_TABLE=0
[ "${1:-}" = "--print-table" ] && PRINT_TABLE=1
[ "${1:-}" = "" ] || [ "$PRINT_TABLE" = 1 ] || {
    echo "usage: $0 [--print-table]" >&2; exit 2;
}

# --------------------------------------------------------------------------
# Pin table derivation. One source, one shape, so the network half below has
# exactly one input path to get right.
#
# The file list is explicit rather than a `provenance/*.env` glob. A glob would
# silently start treating an unrelated env file in that directory as a pin, and
# would silently stop reporting a pin file that had been renamed away - both of
# which are the kind of quiet change in what this script watches for. Naming
# them means deleting one is a visible edit that fails loudly here.
# --------------------------------------------------------------------------

PIN_ENV_FILES=(
    provenance/bepinex.env
    provenance/lsplant.env
    provenance/dobby.env
    provenance/monomod.env
    provenance/il2cppinterop.env
)

derive_pins() {
    local env_file
    for env_file in "${PIN_ENV_FILES[@]}"; do
        [ -f "$env_file" ] || { echo "::error::${env_file} missing" >&2; exit 1; }
        # Sourced in a subshell per file: these files carry more than the pin
        # (patch lists, package versions, feed names) and several of those names
        # are deliberately shared - monomod.env and il2cppinterop.env both define
        # LOCAL_FEED. One shell for all five would let the last one win.
        (
            set -a; . "$env_file"; set +a
            : "${UPSTREAM_URL:?missing from $env_file}"
            : "${UPSTREAM_REF:?missing from $env_file}"
            : "${UPSTREAM_COMMIT:?missing from $env_file}"
            echo "$(basename "$env_file" .env)|${UPSTREAM_URL}|${UPSTREAM_REF}|${UPSTREAM_COMMIT}"
        ) || exit 1
    done
}

# --------------------------------------------------------------------------
# Gitlink agreement - the check that makes the migration window safe.
#
# docs/todo.md Phase 3 replaces submodule gitlinks with these env files, but the
# gitlinks are only deleted in the *last* task. During that window a dependency
# can have a pin in two places: the gitlink that the build actually checks out,
# and the env file this script reports on.
#
# Once derive_pins reads only the env files, a gitlink that moves without its
# env file following is invisible here: the report would say "current" about a
# commit nobody builds. That is a check that quietly passes on stale input -
# the exact failure this repository has removed three times - so the two are
# compared directly and a disagreement is a defect, exit 1, not drift news.
#
# After the gitlinks are removed this compares nothing and says so, rather than
# reporting three pins as verified when it verified nothing.
#
# Gitlinks are read from the INDEX (mode 160000), not from the working tree.
# Two reasons, both learned the hard way:
#
#   - `git ls-tree HEAD` describes a commit, not what is about to be built, so a
#     gitlink staged for deletion still appears there after `git rm --cached`.
#   - a `[ -e third_party/... ]` test reads the working tree, which is empty
#     after the submodules are deinitialised - so the check would silently stop
#     comparing anything and report success. Index mode is the only source that
#     is true in every one of those states.
# --------------------------------------------------------------------------
check_gitlink_agreement() {
    local name link env_commit checked=0
    while IFS=$'\t' read -r link name; do
        [ -n "${link:-}" ] || continue
        name="$(basename "$name")"
        env_commit="$(env_commit_for "$name")" || return 1
        if [ "$env_commit" != "$link" ]; then
            echo "::error::${name}: submodule gitlink ${link} disagrees with provenance/*.env ${env_commit}" >&2
            echo "::error::${name}: two pins for one dependency; a build using the gitlink would not be the commit this report describes" >&2
            return 1
        fi
        echo "  gitlink agrees  ${name}  (${link:0:10})"
        checked=$((checked + 1))
    done < <(git ls-files -s | awk '$1 == 160000 {print $2 "\t" $4}')

    if [ "$checked" -gt 0 ]; then
        [ -f .gitmodules ] || {
            echo "::error::gitlinks are still indexed but .gitmodules is gone" >&2
            echo "::error::remove the gitlinks or restore .gitmodules; a half-removed submodule pins nothing" >&2
            return 1
        }
        return 0
    fi

    if [ -f .gitmodules ]; then
        echo "::error::.gitmodules still exists but no gitlinks are indexed" >&2
        echo "::error::the submodule was half-removed; restore the gitlink or delete .gitmodules" >&2
        return 1
    fi
    echo "  (no gitlinks: pin source is provenance/*.env alone)"
    return 0
}

# Look one pin up by the dependency name. The env files are named in lower case
# (bepinex.env) while the gitlink directories are not (BepInEx), so the match is
# case-insensitive rather than a literal comparison that would silently miss.
env_commit_for() {
    local want="$1" line name commit
    line="$(derive_pins | grep -i "^${want}|")"
    [ -n "$line" ] || {
        echo "::error::no provenance/*.env declares a pin for ${want}" >&2
        echo "::error::a submodule exists with no pin file: nothing here can report its drift" >&2
        return 1
    }
    commit="$(printf '%s\n' "$line" | awk -F'|' '{print $4}')"
    printf '%s\n' "$commit"
}

if [ "$PRINT_TABLE" = 1 ]; then
    derive_pins
    exit 0
fi

if [ -n "${PINS_FILE:-}" ]; then
    [ -f "$PINS_FILE" ] || { echo "PINS_FILE=$PINS_FILE does not exist" >&2; exit 2; }
    PINS="$(grep -v '^[[:space:]]*#' "$PINS_FILE" | grep -v '^[[:space:]]*$')"
else
    PINS="$(derive_pins)"
fi

REPORT="$(mktemp)"
trap 'rm -f "$REPORT"' EXIT

{
echo "============================================================"
echo " upstream pin drift report"
echo "============================================================"

# Cross-check before any network call: a disagreement between the gitlink the
# build checks out and the env file this report is about would make every line
# below wrong, and a network error would hide that. Runs on the derived table
# only, so PINS_FILE fixtures (which point at throwaway local remotes) are not
# made to agree with the real repository's gitlinks.
if [ -z "${PINS_FILE:-}" ]; then
    echo "pin source cross-check:"
    check_gitlink_agreement || exit 1
    echo ""
fi

moved=0
unknown=0
current=0

while IFS='|' read -r name url ref pinned; do
    [ -n "${name:-}" ] || continue
    case "$name" in
        *\ \ *) echo "::error::pin name has a space: '$name'" >&2; exit 1 ;;
    esac
    [ -n "$url" ] && [ -n "$ref" ] && [ -n "$pinned" ] || {
        echo "::error::malformed pin line for '$name': need name|url|ref|pinned" >&2
        exit 1
    }

    remote="$(git ls-remote "$url" "refs/heads/${ref}" 2>/dev/null | awk 'NR==1{print $1}')"
    if [ -z "$remote" ]; then
        echo "  UNKNOWN  $name  ($url refs/heads/$ref unreachable or absent; nothing claimed)"
        unknown=$((unknown + 1))
        continue
    fi
    if [ "$remote" = "$pinned" ]; then
        echo "  current  $name  (pin ${pinned:0:10})"
        current=$((current + 1))
    else
        echo "  MOVED    $name  pin ${pinned:0:10} -> upstream ${remote:0:10}"
        moved=$((moved + 1))
    fi
done <<EOF
$PINS
EOF

echo ""
echo "============================================================"
echo " RESULT: ${current} current, ${moved} moved, ${unknown} unknown"
echo "============================================================"
[ "$moved" -eq 0 ] || cat <<EOF

One or more upstreams moved. This is news, not a failure, and nothing here
was bumped. Moving a pin is deliberate work: check the pin file's own
procedure before touching it - for MonoMod, provenance/monomod.env records
that without the Android patch every Harmony patch fails on device; the
runtime is covered by scripts/check_runtime_pin.sh with its own four-step
procedure.
EOF
[ "$unknown" -eq 0 ] || cat <<EOF

One or more upstreams could not be asked. That is a network/upstream
observation only; it says nothing about whether those pins moved.
EOF
} >"$REPORT"

cat "$REPORT"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### upstream pin drift"
        echo ""
        echo '```'
        cat "$REPORT"
        echo '```'
    } >>"$GITHUB_STEP_SUMMARY"
fi

exit 0
