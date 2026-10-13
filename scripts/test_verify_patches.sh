#!/usr/bin/env bash
# Fixture: scripts/verify_patches.sh gates the replayed patch queue on the
# tree-object hash of the patched sources.
#
# WHY THIS EXISTS
#
# `git am` mints commits with committer dates of "now", so the tip SHA can
# never be compared across runs - but the tree it points at is a pure content
# hash, and replaying the same pin plus the same ordered queue always yields
# the same tree. The gate therefore refuses everything the tree hash cannot
# vouch for. A gate that silently passes any of these is worse than no gate,
# so the fixture is mostly about the refusals.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/verify-patches.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

SCRIPT="$REPO/scripts/verify_patches.sh"
GIT=(git -c user.name=fixture -c user.email=fixture@localhost -c commit.gpgsign=false -c init.defaultBranch=main)

# fresh_repo <dir> : new repo with one committed file; echoes its HEAD (the pin).
fresh_repo() {
    local dir="$1"
    mkdir -p "$dir"
    "${GIT[@]}" -C "$dir" init -q
    echo "original" > "$dir/file.txt"
    "${GIT[@]}" -C "$dir" add file.txt
    "${GIT[@]}" -C "$dir" commit -qm init
    "${GIT[@]}" -C "$dir" rev-parse HEAD
}

# make_mail_patch <dir> <out> : a git-format-patch mail changing file.txt,
# like the entries of the real queue; the repo is left at the pin.
make_mail_patch() {
    local dir="$1" out="$2"
    ( cd "$dir" && echo "patched" >> file.txt && "${GIT[@]}" commit -qam change )
    ( cd "$dir" && "${GIT[@]}" format-patch -1 HEAD --stdout > "$out" )
    ( cd "$dir" && "${GIT[@]}" reset -q --hard HEAD~1 )
}

# replay <dir> <patch> : git-am the patch like apply_patches.sh does.
replay() {
    "${GIT[@]}" -C "$1" am -q --3way "$2"
}

# tree_of <dir> : the tree-object hash the gate compares.
tree_of() {
    git -C "$1" rev-parse "HEAD^{tree}"
}

fails=0
run_case() { # run_case <name> <want-exit> <repo> <pin> <expected-file>
    local name="$1" want="$2" repo="$3" pin="$4" expected="$5" rc
    if "$SCRIPT" "$repo" "$pin" "$expected" >/dev/null 2>&1; then rc=0; else rc=$?; fi
    if [ "$rc" -eq "$want" ]; then
        echo "ok    $name (exit $rc)"
    else
        echo "FAIL  $name: expected exit $want, got $rc"
        fails=$((fails + 1))
    fi
}

echo "== verify_patches.sh =="

# 1. A clean replay against the recorded hash passes.
PIN="$(fresh_repo "$WORK/case1")"
make_mail_patch "$WORK/case1" "$WORK/fix.patch"
replay "$WORK/case1" "$WORK/fix.patch"
tree_of "$WORK/case1" > "$WORK/expected.sha256"
run_case "clean replay passes" 0 "$WORK/case1" "$PIN" "$WORK/expected.sha256"

# 2. A tampered tree (extra commit on top of the replay) fails and says so.
PIN="$(fresh_repo "$WORK/case2")"
replay "$WORK/case2" "$WORK/fix.patch"
tree_of "$WORK/case2" > "$WORK/expected2.sha256"
echo "evil" >> "$WORK/case2/file.txt"
"${GIT[@]}" -C "$WORK/case2" commit -qam tamper
if "$SCRIPT" "$WORK/case2" "$PIN" "$WORK/expected2.sha256" >"$WORK/case2.out" 2>&1; then rc=0; else rc=$?; fi
out="$(cat "$WORK/case2.out")"
if [ "$rc" -eq 1 ] && echo "$out" | grep -q "does not match recorded"; then
    echo "ok    tampered tree fails and names the mismatch (exit 1)"
else
    echo "FAIL  tampered tree: expected exit 1 naming the mismatch, got exit $rc"
    echo "$out"
    fails=$((fails + 1))
fi

# 3. An untracked file fails: the tree hash cannot see it, so the gate must.
PIN="$(fresh_repo "$WORK/case3")"
replay "$WORK/case3" "$WORK/fix.patch"
tree_of "$WORK/case3" > "$WORK/expected3.sha256"
touch "$WORK/case3/stray.bin"
run_case "untracked file fails" 1 "$WORK/case3" "$PIN" "$WORK/expected3.sha256"

# 4. A tree not built on the pin fails, even with a valid commit as the pin.
PIN="$(fresh_repo "$WORK/case4")"
"${GIT[@]}" -C "$WORK/case4" commit -q --allow-empty -m decoy
DECOY="$("${GIT[@]}" -C "$WORK/case4" rev-parse HEAD)"
"${GIT[@]}" -C "$WORK/case4" checkout -q "$PIN"
replay "$WORK/case4" "$WORK/fix.patch"
tree_of "$WORK/case4" > "$WORK/expected4.sha256"
run_case "tree not built on the pin fails" 1 "$WORK/case4" "$DECOY" "$WORK/expected4.sha256"

# 5. A missing record file fails (fail closed, not silent pass).
PIN="$(fresh_repo "$WORK/case5")"
replay "$WORK/case5" "$WORK/fix.patch"
run_case "missing record file fails" 1 "$WORK/case5" "$PIN" "$WORK/no-such-file.sha256"

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
