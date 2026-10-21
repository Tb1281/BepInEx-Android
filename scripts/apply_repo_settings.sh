#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - apply the repository protections the release contract needs
# ==============================================================================
#
# WHAT THIS IS FOR
#
# The release path is only as strong as the branch and tag protections around
# it. This script is the single, reviewable definition of those settings:
#
#   - main requires a pull request, the provenance and patch-queue checks,
#     conversation resolution, and linear history. Force pushes and deletions
#     are blocked, and administrators are included.
#   - release tags (refs/tags/v*) are protected against update and deletion;
#     creation is restricted to maintainers. The moving `verified` tag is
#     explicitly excluded so verify-provenance can keep moving it.
#   - the `release` environment accepts deployments only from main and v* tags.
#     The four release-key secret names must exist; values are never read or
#     printed.
#   - the default workflow token is read-only. GitHub Actions may create pull
#     requests because sync-upstream opens the pin-bump PR; no workflow approves
#     them, and branch protection requires zero approvals anyway.
#
# The script is idempotent: run it as often as needed. It updates the branch
# protection, the `release-tags` ruleset, and the environment policies; a second
# run does not create duplicate tag rules or deployment policies.
#
# USAGE
#   GH_TOKEN=... GITHUB_REPOSITORY=owner/name scripts/apply_repo_settings.sh --dry-run
#   GH_TOKEN=... GITHUB_REPOSITORY=owner/name scripts/apply_repo_settings.sh --apply
#
# --dry-run is the default. It reads the current state and prints every change
# it would make without calling a mutating endpoint.
#
# REQUIREMENTS
#   gh, a token with administration permission on GITHUB_REPOSITORY, and the
#   `release` environment with its four secrets. The script fails closed before
#   any mutation when the token is not an administrator or a secret name is
#   missing.
# ==============================================================================
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-}"
DRY_RUN=1

die() {
    echo "apply_repo_settings: $*" >&2
    exit 1
}

say() {
    printf '%s\n' "$*"
}

usage() {
    sed -n '2,55p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        --apply) DRY_RUN=0 ;;
        --help|-h) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

[ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ] \
    || die "GH_TOKEN is required (use an administrator token; the workflow token cannot change repository settings)"
export GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"

[[ "$REPO" =~ ^[^/]+/[^/]+$ ]] \
    || die "GITHUB_REPOSITORY must be owner/name, got '${REPO:-<empty>}'"
command -v gh >/dev/null 2>&1 || die "gh is not on PATH"

# Read-only calls are always executed, even in dry-run: they are how the plan
# and the idempotency checks see the current state.
get() { # get <endpoint> [jq-expression]
    local endpoint="$1"
    local jq_expr="${2:-}"
    if [ -n "$jq_expr" ]; then
        gh api "$endpoint" --jq "$jq_expr"
    else
        gh api "$endpoint"
    fi
}

mutate() { # mutate <method> <endpoint> [json-body]
    local method="$1"
    local endpoint="$2"
    local body="${3:-}"
    if [ "$DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN: %s %s\n' "$method" "$endpoint"
        return 0
    fi
    if [ "$method" = "DELETE" ]; then
        gh api --method DELETE "$endpoint" >/dev/null
    else
        printf '%s' "$body" | gh api --method "$method" "$endpoint" --input - >/dev/null
    fi
    printf 'APPLIED: %s %s\n' "$method" "$endpoint"
}

if [ "$DRY_RUN" -eq 1 ]; then
    say "apply_repo_settings: dry-run (no mutating calls)"
else
    say "apply_repo_settings: applying repository settings"
fi

admin="$(get "repos/$REPO" '.permissions.admin')" \
    || die "cannot read repository $REPO"
[ "$admin" = "true" ] \
    || die "GH_TOKEN is not an administrator of $REPO; refusing to change protections"

# Validate the release environment before the first mutation. Mutating first and
# discovering an unbuildable environment afterwards leaves a half-applied
# contract; this read-only check keeps a bad token or missing secret from
# changing anything. The API exposes names, never values.
secret_names="$(get "repos/$REPO/environments/release/secrets" '.secrets[]?.name')" \
    || die "cannot read release environment secret names"
missing=()
for secret_name in \
    RELEASE_KEYSTORE_BASE64 \
    RELEASE_KEYSTORE_PASSWORD \
    RELEASE_KEY_ALIAS \
    RELEASE_KEY_PASSWORD; do
    if ! printf '%s\n' "$secret_names" | grep -qx "$secret_name"; then
        missing+=("$secret_name")
    fi
done
if [ "${#missing[@]}" -ne 0 ]; then
    die "release environment is missing secret names: ${missing[*]} (values are never read)"
fi
say "release environment secret names present: RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD"

# GitHub reports check-run names, not the workflow name. These are the two jobs
# in verify-provenance.yml that must pass before main can merge. Requiring the
# workflow name itself would leave every pull request pending forever.
#
# Send only `contexts`, not `checks`. The API rejects a payload that carries
# both arrays (422, "more than one subschema matched"); `contexts` is the
# accepted form and does not require hard-coding an app id.
#
# A pull request is required, but a solo maintainer must be able to merge it:
# GitHub forbids self-approval, so the required approval count is zero.
BRANCH_BODY="$(cat <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["provenance", "patch-queue"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 0
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "block_creations": false,
  "lock_branch": false,
  "allow_fork_syncing": true
}
JSON
)"

# One active ruleset covers create/update/delete for release tags. The moving
# `verified` tag is excluded: mark_verified.sh updates it on every green main
# build, and protecting it would stop the coverage gate. Repository admins and
# maintainers are the only bypass actors, which is what "creation restricted to
# maintainers" means for a user-owned repository.
TAG_BODY="$(cat <<'JSON'
{
  "name": "release-tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/tags/v*"],
      "exclude": ["refs/tags/verified"]
    }
  },
  "rules": [
    {"type": "creation"},
    {"type": "update"},
    {"type": "deletion"}
  ],
  "bypass_actors": [
    {"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"},
    {"actor_id": 4, "actor_type": "RepositoryRole", "bypass_mode": "always"}
  ]
}
JSON
)"

ENV_BODY="$(cat <<'JSON'
{
  "deployment_branch_policy": {
    "protected_branches": false,
    "custom_branch_policies": true
  }
}
JSON
)"

# create-pull-request cannot open the sync PR while this is false: GitHub
# rejects the call with "GitHub Actions is not permitted to create or approve
# pull requests" regardless of the job's pull-requests: write scope. The
# setting also permits approvals, but no workflow approves, and the required
# approval count is zero, so it cannot satisfy a review gate.
TOKEN_BODY="$(cat <<'JSON'
{
  "default_workflow_permissions": "read",
  "can_approve_pull_request_reviews": true
}
JSON
)"

# Branch protection is a PUT, so it is idempotent without a read-modify-write.
mutate PUT "repos/$REPO/branches/main/protection" "$BRANCH_BODY"

# Rulesets are created once and updated by id. The read is what makes a second
# apply an update instead of a duplicate.
ruleset_id="$(get "repos/$REPO/rulesets?targets=tag" \
    '.[]? | select(.name == "release-tags") | .id' | head -1)" \
    || die "cannot list tag rulesets"
case "$ruleset_id" in
    ''|null)
        mutate POST "repos/$REPO/rulesets" "$TAG_BODY"
        ;;
    *)
        mutate PUT "repos/$REPO/rulesets/$ruleset_id" "$TAG_BODY"
        ;;
esac

# The environment PUT preserves the existing secrets; it only changes which
# refs may deploy to the environment.
mutate PUT "repos/$REPO/environments/release" "$ENV_BODY"

# A 404 here means the environment has no custom policy list yet (the common
# state before this script runs); the PUT above switches it to custom mode, so
# treat the list as empty. Any other failure is real and aborts the apply.
if ! policies="$(get "repos/$REPO/environments/release/deployment-branch-policies" \
    '.branch_policies[]? | "\(.id)\t\(.name)\t\(.type)"' 2>&1)"; then
    case "$policies" in
        *"HTTP 404"*) policies="" ;;
        *) die "cannot list release environment deployment policies: $policies" ;;
    esac
fi
# "Only main and v* tags" is enforced by deleting any other custom policy.
# Existing matching policies are left alone, so a second apply is a no-op for
# this list.
declare -A have_policy=()
while IFS=$'\t' read -r policy_id policy_name policy_type; do
    [ -n "${policy_id:-}" ] || continue
    have_policy["${policy_type}:${policy_name}"]=1
    case "${policy_type}:${policy_name}" in
        branch:main|tag:v\*) ;;
        *)
            mutate DELETE "repos/$REPO/environments/release/deployment-branch-policies/$policy_id"
            ;;
    esac
done <<< "$policies"
for spec in "branch:main" "tag:v*"; do
    if [ -z "${have_policy[$spec]:-}" ]; then
        policy_type="${spec%%:*}"
        policy_name="${spec#*:}"
        policy_body="$(printf '{"name":"%s","type":"%s"}' "$policy_name" "$policy_type")"
        mutate POST "repos/$REPO/environments/release/deployment-branch-policies" "$policy_body"
    fi
done

# The release-tag build already requests contents: read and the ship job
# requests contents: write explicitly, so this default only closes the paths
# that forget to declare permissions.
mutate PUT "repos/$REPO/actions/permissions/workflow" "$TOKEN_BODY"

if [ "$DRY_RUN" -eq 1 ]; then
    say "apply_repo_settings: dry-run complete; no changes were made"
else
    say "apply_repo_settings: settings applied"
fi
