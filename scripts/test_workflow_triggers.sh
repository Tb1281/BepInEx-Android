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
# WHAT IT DELIBERATELY DOES NOT CHECK
#
# Whether the lists are *complete*. A path that belongs in both lists but is in
# neither is invisible here - the lists would agree. That is a different question,
# and the answer to it is a review, not a mechanical check.
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
if [ "$fails" -eq 0 ]; then
    echo "pass"
else
    echo "$fails failure(s)"
fi
exit "$fails"