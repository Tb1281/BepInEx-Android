#!/usr/bin/env bash
# Assert that a workflow's duplicated trigger path lists cannot drift apart.
#
# WHY THIS EXISTS
#
# verify-provenance.yml lists its `push:` and `pull_request:` paths twice, once
# per trigger, because GitHub Actions has no way to share one list between them
# (no anchors, no includes - `paths:` cannot reference a file). The two copies
# are identical today.
#
# That is a latent failure of exactly the kind this repository keeps removing: a
# path added to one trigger and not the other means the gate runs for one kind of
# change and not the other, and the only symptom is a check that stopped running.
# It cannot be caught by reading the YAML - the two lists look fine side by side,
# and the diff between them is the whole point.
#
# So it is asserted, in the same spirit as the other fixtures here: the thing that
# would otherwise rot quietly is checked by something that runs on every push.
#
# WHAT IT CHECKS
#
# For each workflow that has both `push:` and `pull_request:` with a `paths:`
# list, the two lists must be identical as sets. Order is ignored (a reordering
# is a no-op) but additions and removals are not. A workflow with only one
# trigger, or with no `paths:` filter at all, is not this fixture's business.
#
# It also asserts the release contract's trigger and job shape: the tag release
# is `v[0-9]*` on a tag push only, `sync-upstream` is schedule/manual only and
# has exactly one job (no ship job) that never runs `gh release create`,
# `ship-release` is call-only and verifies the pushed tag, consumer verification
# is release/manual, promotion is manual only, and every job that reads
# `secrets.RELEASE_` declares the `release` environment. Those are the triggers
# and job boundaries that would broaden a release path or reintroduce a sync
# release without any host-side change.
#
# WHAT IT DELIBERATELY DOES NOT CHECK
#
# Whether the path lists are *complete*. A path that belongs in both lists but
# is in neither is invisible here - the lists would agree. That is a different
# question, and the answer to it is a review, not a mechanical check.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

fails=0

paths_of() { # paths_of <workflow> <trigger> -> newline-separated path entries
    python3 - "$1" "$2" <<'PY'
import sys, pathlib
try:
    import yaml
except ImportError:
    sys.exit(3)
doc = yaml.safe_load(pathlib.Path(sys.argv[1]).read_text())
# PyYAML resolves the bare key `on` to the boolean True.
on = doc.get('on', doc.get(True))
if not isinstance(on, dict):
    sys.exit(0)
trig = on.get(sys.argv[2])
if not isinstance(trig, dict):
    sys.exit(0)
paths = trig.get('paths')
if not isinstance(paths, list):
    sys.exit(0)
for p in paths:
    print(p)
PY
}

echo "== workflow trigger path lists =="
echo

for wf in .github/workflows/*.yml; do
    name="${wf##*/}"
    push="$(paths_of "$wf" push)"  || { echo "  skip  $name (no python/yaml)"; continue; }
    pr="$(paths_of "$wf" pull_request)" || continue

    # Not every workflow has both triggers, or uses paths at all. A workflow with
    # a single trigger is not a drift risk; say so rather than silently passing.
    if [ -z "$push" ] && [ -z "$pr" ]; then
        continue
    fi
    if [ -z "$pr" ]; then
        echo "ok    $name: push-only, nothing to keep in sync"
        continue
    fi
    if [ -z "$push" ]; then
        echo "FAIL  $name: pull_request filters paths but push does not"
        fails=$((fails + 1))
        continue
    fi

    # Compare as sets: a reorder is not a drift, an added or removed path is.
    push_sorted="$(printf '%s\n' "$push" | LC_ALL=C sort)"
    pr_sorted="$(printf '%s\n' "$pr" | LC_ALL=C sort)"

    if [ "$push_sorted" = "$pr_sorted" ]; then
        n="$(printf '%s\n' "$push_sorted" | grep -c .)"
        echo "ok    $name: push and pull_request agree ($n paths)"
    else
        echo "FAIL  $name: the two triggers disagree"
        echo "        only in push:"
        printf '%s\n' "$(comm -23 <(printf '%s\n' "$push_sorted") <(printf '%s\n' "$pr_sorted") | sed 's/^/          /')"
        echo "        only in pull_request:"
        printf '%s\n' "$(comm -13 <(printf '%s\n' "$push_sorted") <(printf '%s\n' "$pr_sorted") | sed 's/^/          /')"
        echo "        a path in one trigger but not the other means the gate does not"
        echo "        run for that kind of change, and nothing else reports it."
        fails=$((fails + 1))
    fi
done

echo
echo "== release contract triggers =="
echo

python3 - <<'PY'
import json
import pathlib
import sys

try:
    import yaml
except ImportError:
    print("  skip  release contract triggers (no python/yaml)")
    raise SystemExit(0)

ROOT = pathlib.Path(".github/workflows")
failures = 0


def load(name):
    doc = yaml.safe_load((ROOT / name).read_text())
    # PyYAML resolves the bare key `on` to the boolean True.
    on = doc.get("on", doc.get(True))
    return on if isinstance(on, dict) else {}


def check(name, condition, detail):
    global failures
    if condition:
        print(f"  ok    {name}")
    else:
        print(f"  FAIL  {name}: {detail}")
        failures += 1


release = load("release-tag.yml")
check("release-tag is tag-push only", set(release) == {"push"}, sorted(release))
tag_push = release.get("push") or {}
check(
    "release-tag matches v[0-9]* and nothing broader",
    tag_push.get("tags") == ["v[0-9]*"]
    and "branches" not in tag_push
    and "paths" not in tag_push,
    tag_push,
)
check(
    "release-tag cannot match the moving verified tag",
    tag_push.get("tags") != ["v*"],
    tag_push.get("tags"),
)

sync = load("sync-upstream.yml")
check(
    "sync-upstream is schedule/manual only",
    set(sync) == {"schedule", "workflow_dispatch"},
    sorted(sync),
)
check(
    "sync-upstream has no release or push trigger",
    "release" not in sync and "push" not in sync and "pull_request" not in sync,
    sorted(sync),
)

sync_doc = yaml.safe_load((ROOT / "sync-upstream.yml").read_text())
sync_jobs = sync_doc.get("jobs", {})
check(
    "sync-upstream has exactly one job, named sync",
    set(sync_jobs) == {"sync"},
    sorted(sync_jobs),
)
sync_text = (ROOT / "sync-upstream.yml").read_text()
check(
    "sync-upstream does not create releases",
    "gh release create" not in sync_text,
    "gh release create present",
)

sync_steps = sync_doc.get("jobs", {}).get("sync", {}).get("steps", [])
pr_steps = [s for s in sync_steps if s.get("name") == "Create PR for pin update"]
check(
    "sync-upstream has exactly one pin-bump PR step",
    len(pr_steps) == 1,
    len(pr_steps),
)
add_paths = str(pr_steps[0].get("with", {}).get("add-paths", "")) if pr_steps else ""
check(
    "sync PR commits the pin, its generated Pin Table, and the patched-tree hash",
    set(add_paths.split()) == {
        "provenance/bepinex.env",
        "provenance/SOURCES.md",
        "references/patched-tree.sha256",
    },
    add_paths,
)
sync_step_names = {s.get("name") for s in sync_steps if s.get("name")}
check(
    "sync-upstream regenerates the Pin Table after a pin bump",
    "Regenerate the human-readable pin table" in sync_step_names,
    sorted(sync_step_names),
)
check(
    "sync-upstream checks the regenerated Pin Table",
    "The human-readable pin table matches the pin" in sync_step_names
    and "render_sources_pin_table.py --check" in sync_text,
    sync_text,
)

ship = load("ship-release.yml")
check("ship-release is call-only", set(ship) == {"workflow_call"}, sorted(ship))
ship_text = (ROOT / "ship-release.yml").read_text()
check(
    "ship-release verifies the pushed tag",
    "--verify-tag" in ship_text,
    "--verify-tag missing",
)

# The release environment is the secret boundary. A job that references a
# release secret without declaring it reads nothing at runtime, so the failure
# would surface only on a tag push; assert the boundary statically instead.
for wf in sorted(ROOT.glob("*.yml")):
    doc = yaml.safe_load(wf.read_text())
    jobs = doc.get("jobs") or {}
    for job_name, job in jobs.items():
        if "secrets.RELEASE_" not in json.dumps(job):
            continue
        env = job.get("environment")
        if isinstance(env, dict):
            env = env.get("name")
        check(
            f"{wf.name}:{job_name} reads release secrets in the release environment",
            env == "release",
            env,
        )

verify = load("verify-release.yml")
check(
    "verify-release is release/manual only",
    set(verify) <= {"release", "workflow_dispatch"} and "release" in verify,
    sorted(verify),
)

promote = load("promote-release.yml")
check(
    "promote-release is manual only",
    set(promote) == {"workflow_dispatch"},
    sorted(promote),
)

gate = load("verify-provenance.yml")
check(
    "verify-provenance has no release trigger",
    "release" not in gate,
    sorted(gate),
)

raise SystemExit(1 if failures else 0)
PY
contract_status=$?
if [ "$contract_status" -ne 0 ]; then
    fails=$((fails + 1))
fi

echo
if [ "$fails" -eq 0 ]; then
    echo "pass"
else
    echo "$fails failure(s)"
fi
exit "$fails"