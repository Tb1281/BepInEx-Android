#!/usr/bin/env bash
# Fixture: every CI job runs step-security/harden-runner with egress audit.
#
# WHY THIS EXISTS
#
# The harden step is what records where a runner talked to. It is one YAML
# block per job, copied seven times across six files, with no shared include —
# the same shape as the duplicated trigger path lists that already rotted once
# (see scripts/test_workflow_triggers.sh). A job added without the block, or a
# block edited from `audit` to nothing, produces no error anywhere: the workflow
# still runs, still greens, and simply stops recording. So it is asserted here.
#
# WHAT IT CHECKS (stdlib only — no PyYAML, so it runs on any runner)
#
#   - every job in every workflow has a harden-runner step;
#   - each use is pinned to a full 40-hex SHA (repo policy: no floating tags);
#   - the harden step is the FIRST step of its job (an exfiltration step placed
#     before it would run unrecorded, which is the failure this exists to stop).
#   - egress policy per job: audit by default; the registered PILOTS below must
#     be block with a non-empty allowed-endpoints list. A job flipped to block
#     without being registered here fails, and a pilot reverted to audit fails —
#     the pilot set is explicit so it cannot rot in either direction.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

# "workflow-file:job" pairs that block instead of audit. The endpoint list
# itself lives in the workflow (single source); this only names the pilots.
PILOTS="verify-provenance.yml:provenance verify-provenance.yml:patch-queue build-bepinex.yml:build"

is_pilot() { # is_pilot <file:job> -> exit 0 if registered
    local key="$1" p
    for p in $PILOTS; do
        [ "$p" = "$key" ] && return 0
    done
    return 1
}

# Per-job policy table: "<file> <job> <audit|block|none>[+allow]".
job_policies() { # job_policies <workflow-file>
    awk '
        /^jobs:/ { inj = 1; next }
        inj && /^  [A-Za-z0-9_-]+:/ {
            if (job != "") print FILENAME " " job " " pol (allow ? "+allow" : "")
            job = $1; sub(/:$/, "", job)
            pol = "none"; allow = 0; want_value = 0
            next
        }
        inj && /^[^ ]/ { next }
        {
            if (want_value) {
                if ($0 ~ /^[[:space:]]*$/) { next }
                if ($0 ~ /^[[:space:]]*[A-Za-z-]+:/) { want_value = 0; next }
                allow = 1; want_value = 0; next
            }
            if ($0 ~ /egress-policy:[[:space:]]*audit/)  pol = "audit"
            if ($0 ~ /egress-policy:[[:space:]]*block/)  pol = "block"
            if ($0 ~ /allowed-endpoints:/) {
                rest = $0; sub(/^[^:]*:/, "", rest); gsub(/^[[:space:]]+|[[:space:]]+$/, "", rest)
                if (rest != "" && rest != ">" && rest != "|") allow = 1
                else want_value = 1
            }
        }
        END {
            if (job != "") print FILENAME " " job " " pol (allow ? "+allow" : "")
        }
    ' "$1"
}

fails=0

for wf in .github/workflows/*.yml; do
    name="${wf##*/}"
    # Jobs: two-space keys after the `jobs:` line (top level is only
    # name/on/jobs, so nothing else matches this shape).
    jobs="$(awk '/^jobs:/{inj=1;next} inj&&/^  [A-Za-z0-9_-]+:/{print $1}' "$wf" | tr -d ':')"
    njobs="$(printf '%s\n' "$jobs" | grep -c . || true)"
    [ "$njobs" -gt 0 ] || continue

    # Pinned uses of harden-runner in this file.
    pinned="$(grep -cE 'uses:[[:space:]]+step-security/harden-runner@[0-9a-f]{40}' "$wf" || true)"
    floating="$(grep -cE 'uses:[[:space:]]+step-security/harden-runner@[^0-9a-f]' "$wf" || true)"

    ok=1
    [ "$pinned" -eq "$njobs" ] || ok=0
    [ "$floating" -eq 0 ] || ok=0
    if [ "$ok" = 1 ]; then
        echo "ok    $name: $njobs job(s), all pinned"
    else
        echo "FAIL  $name: jobs=$njobs pinned=$pinned floating=$floating"
        fails=$((fails + 1))
    fi

    # Per-job policy: audit everywhere except registered pilots (block+allow).
    pol_ok=1
    while read -r _file job pol; do
        [ -n "$job" ] || continue
        if is_pilot "$name:$job"; then
            if [ "$pol" = "block+allow" ]; then
                echo "ok    $name:$job blocks with an allow-list (registered pilot)"
            else
                echo "FAIL  $name:$job is a registered pilot but policy is '$pol', want 'block+allow'"
                pol_ok=0
            fi
        else
            if [ "$pol" = "audit" ]; then
                : # quiet: the file-level line above already reports the count
            else
                echo "FAIL  $name:$job policy is '$pol', want 'audit' (register it in PILOTS to block)"
                pol_ok=0
            fi
        fi
    done < <(job_policies "$wf")
    [ "$pol_ok" = 1 ] || fails=$((fails + 1))

    # First-step ordering: after each `steps:` line, the next non-comment,
    # non-blank line must be the harden step.
    firsts="$(awk '
        /^[[:space:]]*steps:/{want=1;next}
        want&&/^[[:space:]]*#/ {next}
        want&&/^[[:space:]]*$/ {next}
        want{print; want=0}
    ' "$wf")"
    nfirst="$(printf '%s\n' "$firsts" | grep -c . || true)"
    nharden="$(printf '%s\n' "$firsts" | grep -c 'step-security/harden-runner@' || true)"
    if [ "$nfirst" -eq "$njobs" ] && [ "$nharden" -eq "$njobs" ]; then
        echo "ok    $name: harden-runner is the first step in every job"
    else
        echo "FAIL  $name: harden-runner is not first in every job"
        printf '%s\n' "$firsts" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
done

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
