#!/usr/bin/env bash
# Fixture for scripts/apply_repo_settings.sh.
#
# gh is stubbed, so this asserts the API payloads and the decision to mutate,
# not the live repository. The cases that matter are the ones where a wrong
# setting is quiet:
#
#   - dry-run must make no mutating call at all;
#   - a 404 for the environment's custom policy list is an empty list, not an
#     error;
#   - the branch payload must require a PR, both verify-provenance jobs,
#     linear history, conversation resolution, and include administrators;
#   - the tag ruleset must cover v* release tags but exclude the moving
#     `verified` tag, and only admins/maintainers may bypass creation;
#   - the release environment must allow only main and v* tags, and its four
#     secret names must be present without reading values;
#   - a second apply must update, not duplicate, the tag rule or policies;
#   - a token without admin permission must fail before any mutation.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/repo-settings.XXXXXX")"
export WORK
trap 'rm -rf "$WORK"' EXIT

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="example/repo"
SCRIPT="$ROOT/scripts/apply_repo_settings.sh"
export GITHUB_REPOSITORY="$REPO"
export REPO
export GH_TOKEN="dummy-token-value"

mkdir -p "$WORK/bin" "$WORK/bodies"
cat > "$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?}"
printf '%s\n' "$*" >> "$WORK/calls"

method=GET
endpoint=""
jq_expr=""
has_input=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --method) method="$2"; shift 2 ;;
        --jq) jq_expr="$2"; shift 2 ;;
        --input) has_input=1; shift 2 ;;
        --paginate|--silent) shift ;;
        -*) shift ;;
        *) endpoint="$1"; shift ;;
    esac
done

body=""
if [ "$has_input" -eq 1 ]; then
    body="$(cat)"
fi
if [ "$method" != "GET" ]; then
    n="$(cat "$WORK/body-count" 2>/dev/null || echo 0)"
    n=$((n + 1))
    printf '%s\n' "$n" > "$WORK/body-count"
    printf '%s' "$body" > "$WORK/bodies/$n"
    printf '%s %s %s\n' "$method" "$endpoint" "$n" >> "$WORK/mutations"
fi

response='{}'
case "$method $endpoint" in
    "GET repos/$REPO")
        response="{\"permissions\":{\"admin\":$(cat "$WORK/admin")}}"
        ;;
    "GET repos/$REPO/rulesets?targets=tag")
        if [ -f "$WORK/ruleset-id" ]; then
            response="[{\"id\":$(cat "$WORK/ruleset-id"),\"name\":\"release-tags\"}]"
        else
            response='[]'
        fi
        ;;
    "GET repos/$REPO/environments/release/deployment-branch-policies")
        if [ -f "$WORK/policies-404" ]; then
            echo 'gh: Not Found (HTTP 404)' >&2
            exit 1
        fi
        response="$(jq -c '{total_count:length,branch_policies:.}' "$WORK/policies")"
        ;;
    "GET repos/$REPO/environments/release/secrets")
        response="$(jq -c '{total_count:length,secrets:map({name:.})}' "$WORK/secrets")"
        ;;
    "POST repos/$REPO/rulesets")
        printf '42\n' > "$WORK/ruleset-id"
        response='{"id":42}'
        ;;
    "PUT repos/$REPO/rulesets/"*)
        response='{}'
        ;;
    "POST repos/$REPO/environments/release/deployment-branch-policies")
        policy_name="$(jq -r .name "$WORK/bodies/$n")"
        policy_type="$(jq -r .type "$WORK/bodies/$n")"
        next_id="$(jq 'length' "$WORK/policies")"
        policy_id=$((100 + next_id))
        jq --arg name "$policy_name" --arg type "$policy_type" --argjson id "$policy_id" \
            '. + [{id:$id,name:$name,type:$type}]' "$WORK/policies" > "$WORK/policies.tmp"
        mv "$WORK/policies.tmp" "$WORK/policies"
        response="{\"id\":$policy_id,\"name\":\"$policy_name\",\"type\":\"$policy_type\"}"
        ;;
    "DELETE repos/$REPO/environments/release/deployment-branch-policies/"*)
        policy_id="${endpoint##*/}"
        jq --argjson id "$policy_id" 'map(select(.id != $id))' "$WORK/policies" > "$WORK/policies.tmp"
        mv "$WORK/policies.tmp" "$WORK/policies"
        response=''
        ;;
    *)
        response='{}'
        ;;
esac

if [ -n "$jq_expr" ]; then
    printf '%s' "$response" | jq -r "$jq_expr"
else
    printf '%s\n' "$response"
fi
STUB
chmod +x "$WORK/bin/gh"

fails=0
check() { # check <name> <command...>
    local name="$1"; shift
    if "$@" >/dev/null 2>&1; then
        echo "ok    $name"
    else
        echo "FAIL  $name"
        fails=$((fails + 1))
    fi
}

check_absent_text() { # check_absent_text <name> <needle> <file>
    local name="$1" needle="$2" file="$3"
    if grep -q -- "$needle" "$file"; then
        echo "FAIL  $name: '$needle' present"
        fails=$((fails + 1))
    else
        echo "ok    $name"
    fi
}

set_state() { # set_state <admin> <none|ruleset-id> <policies-json> <secrets-json>
    printf '%s\n' "$1" > "$WORK/admin"
    if [ "$2" = "none" ]; then
        rm -f "$WORK/ruleset-id"
    else
        printf '%s\n' "$2" > "$WORK/ruleset-id"
    fi
    printf '%s\n' "$3" > "$WORK/policies"
    printf '%s\n' "$4" > "$WORK/secrets"
}

reset_calls() {
    : > "$WORK/calls"
    : > "$WORK/mutations"
    : > "$WORK/body-count"
    rm -f "$WORK"/bodies/*
}

run_settings() {
    PATH="$WORK/bin:$PATH" "$SCRIPT" "$@" > "$WORK/output" 2>&1
}

body_for() { # body_for <method> <endpoint> -> path to the last body
    local n
    n="$(awk -v m="$1" -v e="$2" '$1 == m && $2 == e { print $3 }' "$WORK/mutations" | tail -1)"
    [ -n "$n" ] && printf '%s\n' "$WORK/bodies/$n"
}

secrets_json='["RELEASE_KEYSTORE_BASE64","RELEASE_KEYSTORE_PASSWORD","RELEASE_KEY_ALIAS","RELEASE_KEY_PASSWORD"]'
policies_initial='[{"id":1,"name":"staging","type":"branch"}]'

echo "== dry-run =="
set_state true none "$policies_initial" "$secrets_json"
reset_calls
if run_settings --dry-run; then
    echo "ok    dry-run exits 0"
else
    echo "FAIL  dry-run exited nonzero"
    fails=$((fails + 1))
fi
if [ ! -s "$WORK/mutations" ]; then
    echo "ok    dry-run mutation log is empty"
else
    echo "FAIL  dry-run wrote a mutation"
    fails=$((fails + 1))
fi
check "dry-run plans branch protection" grep -q "DRY-RUN: PUT repos/$REPO/branches/main/protection" "$WORK/output"
check "dry-run plans the tag ruleset" grep -q "DRY-RUN: POST repos/$REPO/rulesets" "$WORK/output"
check "dry-run plans the release environment policy" grep -q "DRY-RUN: PUT repos/$REPO/environments/release" "$WORK/output"
check "dry-run plans removal of the stale policy" grep -q "DRY-RUN: DELETE repos/$REPO/environments/release/deployment-branch-policies/1" "$WORK/output"
check_absent_text "dry-run never prints APPLIED" "APPLIED" "$WORK/output"

echo
echo "== dry-run with no custom policy list =="
set_state true none '[]' "$secrets_json"
touch "$WORK/policies-404"
reset_calls
if run_settings --dry-run; then
    echo "ok    dry-run with a 404 policy list exits 0"
else
    echo "FAIL  dry-run with a 404 policy list exited nonzero"
    cat "$WORK/output"
    fails=$((fails + 1))
fi
check "a 404 policy list is treated as empty" \
    grep -q "DRY-RUN: POST repos/$REPO/environments/release/deployment-branch-policies" "$WORK/output"
check "the empty list still plans main and v*" \
    bash -c '[ "$(grep -c "DRY-RUN: POST repos/$REPO/environments/release/deployment-branch-policies" "$1")" -eq 2 ]' _ "$WORK/output"
if [ ! -s "$WORK/mutations" ]; then
    echo "ok    the 404 dry-run made no mutation"
else
    echo "FAIL  the 404 dry-run wrote a mutation"
    fails=$((fails + 1))
fi
rm -f "$WORK/policies-404"

echo
echo "== first apply =="
set_state true none "$policies_initial" "$secrets_json"
reset_calls
if run_settings --apply; then
    echo "ok    apply exits 0"
else
    echo "FAIL  apply exited nonzero"
    cat "$WORK/output"
    fails=$((fails + 1))
fi

branch_body="$(body_for PUT "repos/$REPO/branches/main/protection")"
check "branch protection requires both checks" \
    jq -e '.required_status_checks.strict == true and (.required_status_checks.contexts | index("provenance")) != null and (.required_status_checks.contexts | index("patch-queue")) != null' "$branch_body"
check "branch protection sends only the accepted contexts field" \
    jq -e '.required_status_checks | has("checks") | not' "$branch_body"
check "branch protection requires a pull request" \
    jq -e '.required_pull_request_reviews != null and .required_pull_request_reviews.required_approving_review_count == 0' "$branch_body"
check "branch protection requires linear history" \
    jq -e '.required_linear_history == true' "$branch_body"
check "branch protection resolves conversations" \
    jq -e '.required_conversation_resolution == true' "$branch_body"
check "branch protection blocks force pushes and deletions" \
    jq -e '.allow_force_pushes == false and .allow_deletions == false' "$branch_body"
check "branch protection includes administrators" \
    jq -e '.enforce_admins == true' "$branch_body"
check "branch protection has no push restrictions" \
    jq -e '.restrictions == null' "$branch_body"

ruleset_body="$(body_for POST "repos/$REPO/rulesets")"
check "tag ruleset protects v* and excludes verified" \
    jq -e '.target == "tag" and .conditions.ref_name.include == ["refs/tags/v*"] and .conditions.ref_name.exclude == ["refs/tags/verified"]' "$ruleset_body"
check "tag ruleset covers creation, update, and deletion" \
    jq -e '([.rules[].type] | index("creation")) != null and ([.rules[].type] | index("update")) != null and ([.rules[].type] | index("deletion")) != null' "$ruleset_body"
check "tag ruleset bypasses admins and maintainers" \
    jq -e '([.bypass_actors[] | select(.actor_type == "RepositoryRole" and .bypass_mode == "always" and (.actor_id == 5 or .actor_id == 4))] | length) == 2' "$ruleset_body"
check "first apply creates exactly one tag ruleset" \
    bash -c '[ "$(grep -c "POST repos/$REPO/rulesets" "$1")" -eq 1 ]' _ "$WORK/mutations"

env_body="$(body_for PUT "repos/$REPO/environments/release")"
check "release environment uses custom ref policies" \
    jq -e '.deployment_branch_policy.custom_branch_policies == true and .deployment_branch_policy.protected_branches == false' "$env_body"
check "release environment creates main and v* policies" \
    bash -c 'out="$(for n in $(awk "\$1 == \"POST\" && \$2 ~ /deployment-branch-policies$/ { print \$3 }" "$1"); do jq -r ".name + \":\" + .type" "$2/$n"; done | sort)"; [ "$out" = "main:branch
v*:tag" ]' _ "$WORK/mutations" "$WORK/bodies"
check "release environment removes the stale policy" \
    grep -q "DELETE repos/$REPO/environments/release/deployment-branch-policies/1" "$WORK/mutations"
check "first apply creates exactly two deployment policies" \
    bash -c '[ "$(grep -c "POST repos/$REPO/environments/release/deployment-branch-policies" "$1")" -eq 2 ]' _ "$WORK/mutations"

token_body="$(body_for PUT "repos/$REPO/actions/permissions/workflow")"
check "default workflow token is read-only" \
    jq -e '.default_workflow_permissions == "read"' "$token_body"
check "Actions cannot approve pull requests" \
    jq -e '.can_approve_pull_request_reviews == false' "$token_body"
check "secret names are reported" \
    grep -q "release environment secret names present: RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD" "$WORK/output"
check_absent_text "secret values are never printed" "dummy-secret-value" "$WORK/output"

echo
echo "== second apply =="
reset_calls
if run_settings --apply; then
    echo "ok    second apply exits 0"
else
    echo "FAIL  second apply exited nonzero"
    cat "$WORK/output"
    fails=$((fails + 1))
fi
check "second apply updates the existing ruleset" \
    grep -q "PUT repos/$REPO/rulesets/42" "$WORK/mutations"
check_absent_text "second apply does not create another tag rule" "POST repos/$REPO/rulesets" "$WORK/mutations"
check_absent_text "second apply does not create another policy" "POST repos/$REPO/environments/release/deployment-branch-policies" "$WORK/mutations"
check_absent_text "second apply does not delete a valid policy" "DELETE repos/$REPO/environments/release/deployment-branch-policies" "$WORK/mutations"
check "second apply still updates branch protection" \
    grep -q "PUT repos/$REPO/branches/main/protection" "$WORK/mutations"

echo
echo "== fail closed without admin =="
set_state false 42 '[]' "$secrets_json"
reset_calls
if run_settings --apply; then
    echo "FAIL  apply with a non-admin token exited 0"
    fails=$((fails + 1))
else
    echo "ok    apply with a non-admin token fails"
fi
check "non-admin failure names the permission" \
    grep -q "not an administrator" "$WORK/output"
check "non-admin failure makes no mutating call" \
    bash -c '[ ! -s "$1" ]' _ "$WORK/mutations"

echo
if [ "$fails" -eq 0 ]; then
    echo "ALL FIXTURE CASES PASSED"
    exit 0
fi
echo "$fails CASE(S) FAILED"
exit 1
