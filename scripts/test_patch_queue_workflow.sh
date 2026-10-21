#!/usr/bin/env bash
# Fixture: the patch queue's sync/rebaseline recovery path.
#
# WHY THIS EXISTS
#
# A conflict is the expected result of an upstream move, not a malformed
# queue. What must survive is the recovery path: the pin bump, the report a
# human reads, and the refusal to record a tree the queue did not produce.
# None of it was exercised before this fixture, and three defects hid in it:
# sync_upstream.sh compared an absolute pin path against repo-relative
# `git status` output, report_patch_conflict.sh enumerated the real queue
# instead of BEPINEX_PATCH_GLOB, and record_patched_tree.sh would record a
# prefix of the queue.
#
# Every remote here is a local path. No network.
#
# Cases are added one at a time, each against the public interface:
#   sync_upstream.sh          pin file, git index, GITHUB_OUTPUT, exit
#   apply_patches.sh +
#   report_patch_conflict.sh  BEPINEX_SRC/PIN_FILE/PATCH_GLOB, report text
#   record_patched_tree.sh    scratch tree, queue glob, recorded hash, exit
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/patch-queue.XXXXXX")"

# The patched-tree record is a repository file. Until the fixture uses its
# override, a red run could overwrite it; restore it on every exit so a test
# run can never change what the repository says it built.
REAL_TREE="$REPO/references/patched-tree.sha256"
cp "$REAL_TREE" "$WORK/patched-tree.real"
cleanup() {
    cp "$WORK/patched-tree.real" "$REAL_TREE"
    rm -rf "$WORK"
}
trap cleanup EXIT

GIT=(git -c user.name=fixture -c user.email=fixture@localhost -c commit.gpgsign=false -c init.defaultBranch=main)
g() { "${GIT[@]}" "$@"; }

fails=0
pass() { echo "ok    $1"; }
fail() {
    echo "FAIL  $1"
    shift
    if [ "$#" -gt 0 ]; then printf '%s\n' "$@"; fi
    fails=$((fails + 1))
}
contains() { # contains <name> <file> <literal>
    local name="$1" file="$2" needle="$3"
    if grep -Fq -- "$needle" "$file"; then
        pass "$name"
    else
        fail "$name" "missing: $needle" "--- $file ---" "$(sed -n '1,160p' "$file")"
    fi
}

# --- synthetic upstream -----------------------------------------------------
# OLD is the commit the patch was written against; NEW is the pin upstream
# moved to. The patch changes the same line NEW changes, so the replay must
# conflict.
UPSTREAM="$WORK/upstream"
mkdir -p "$UPSTREAM"
g -C "$UPSTREAM" init -q
printf 'alpha\nbeta\ngamma\n' > "$UPSTREAM/src.txt"
printf 'one\ntwo\nthree\n' > "$UPSTREAM/other.txt"
g -C "$UPSTREAM" add src.txt other.txt
g -C "$UPSTREAM" commit -qm "upstream base"
g -C "$UPSTREAM" tag v0.1.0
OLD="$(g -C "$UPSTREAM" rev-parse HEAD)"

QUEUE="$WORK/queue"
mkdir -p "$QUEUE"
PATCHGEN="$WORK/patchgen"
g clone -q "$UPSTREAM" "$PATCHGEN"
g -C "$PATCHGEN" checkout -q "$OLD"
sed -i 's/^beta$/beta-patched/' "$PATCHGEN/src.txt"
sed -i 's/^two$/two-patched/' "$PATCHGEN/other.txt"
g -C "$PATCHGEN" commit -qam "port: change beta and two"
g -C "$PATCHGEN" format-patch -1 HEAD --stdout > "$QUEUE/0001-change-beta.patch"

sed -i 's/^beta$/beta-upstream/' "$UPSTREAM/src.txt"
sed -i 's/^two$/two-upstream/' "$UPSTREAM/other.txt"
g -C "$UPSTREAM" commit -qam "upstream: change beta and two"
NEW="$(g -C "$UPSTREAM" rev-parse HEAD)"
NEW_DESCRIBE="$(g -C "$UPSTREAM" describe --tags --always "$NEW")"
OLD_DESCRIBE="$(g -C "$UPSTREAM" describe --tags --always "$OLD")"

# --- pin repo ---------------------------------------------------------------
PINREPO="$WORK/pinrepo"
mkdir -p "$PINREPO/provenance"
g -C "$PINREPO" init -q
write_pin_to() { # write_pin_to <pin-repo> <commit> <describe> [upstream-url]
    local pinrepo="$1"
    cat > "$pinrepo/provenance/bepinex.env" <<EOF
# synthetic BepInEx pin for the fixture
UPSTREAM_URL=${4:-$UPSTREAM}
UPSTREAM_REF=main
UPSTREAM_COMMIT=$2
UPSTREAM_DESCRIBE=$3
EOF
}
write_pin_to "$PINREPO" "$OLD" "$OLD_DESCRIBE"
g -C "$PINREPO" add provenance/bepinex.env
g -C "$PINREPO" commit -qm "pin at old"
PIN_FILE="$PINREPO/provenance/bepinex.env"

run_sync() {
    : > "$WORK/github-output"
    if env BEPINEX_PIN_FILE="$PIN_FILE" GITHUB_OUTPUT="$WORK/github-output" \
        "$REPO/scripts/sync_upstream.sh" >"$WORK/sync.out" 2>&1; then
        SYNC_RC=0
    else
        SYNC_RC=$?
    fi
}

echo "== sync_upstream.sh: clean move =="
run_sync
if [ "$SYNC_RC" -eq 0 ]; then
    pass "clean move exits 0"
else
    fail "clean move exits 0" "exit $SYNC_RC" "$(cat "$WORK/sync.out")"
fi
contains "clean move updates UPSTREAM_COMMIT" "$PIN_FILE" "UPSTREAM_COMMIT=$NEW"
contains "clean move updates UPSTREAM_DESCRIBE" "$PIN_FILE" "UPSTREAM_DESCRIBE=$NEW_DESCRIBE"
contains "clean move reports moved=true" "$WORK/github-output" "moved=true"
contains "clean move reports old" "$WORK/github-output" "old=$OLD"
contains "clean move reports new" "$WORK/github-output" "new=$NEW"
if g -C "$PINREPO" diff --cached --name-only | grep -qx 'provenance/bepinex.env'; then
    pass "clean move stages the pin"
else
    fail "clean move stages the pin" "$(g -C "$PINREPO" status --short)"
fi

echo "== sync_upstream.sh: dirty pin =="
g -C "$PINREPO" reset -q --hard HEAD
printf '\n# local edit\n' >> "$PIN_FILE"
run_sync
if [ "$SYNC_RC" -eq 1 ]; then
    pass "dirty pin exits 1"
else
    fail "dirty pin exits 1" "exit $SYNC_RC" "$(cat "$WORK/sync.out")"
fi
contains "dirty pin names the refusal" "$WORK/sync.out" \
    "refusing to sync: provenance/bepinex.env has uncommitted changes"
if grep -Fq "UPSTREAM_COMMIT=$OLD" "$PIN_FILE"; then
    pass "dirty pin leaves the pin unchanged"
else
    fail "dirty pin leaves the pin unchanged" "$(cat "$PIN_FILE")"
fi
if g -C "$PINREPO" diff --cached --quiet; then
    pass "dirty pin stages nothing"
else
    fail "dirty pin stages nothing" "$(g -C "$PINREPO" status --short)"
fi
g -C "$PINREPO" checkout -q -- provenance/bepinex.env

echo "== report_patch_conflict.sh: conflict =="
write_pin_to "$PINREPO" "$NEW" "$NEW_DESCRIBE"
g -C "$PINREPO" add provenance/bepinex.env
g -C "$PINREPO" commit -qm "pin at new"
SCRATCH="$WORK/scratch-conflict"
CONFLICT_OUT="$WORK/conflict.out"
CONFLICT_SUMMARY="$WORK/conflict-summary"
: > "$CONFLICT_SUMMARY"
if env BEPINEX_PIN_FILE="$PIN_FILE" BEPINEX_SRC="$SCRATCH" \
    BEPINEX_PATCH_GLOB="$QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$WORK/conflict-tree.sha256" \
    BEPINEX_SKIP_TREE_GATE=1 GITHUB_STEP_SUMMARY="$CONFLICT_SUMMARY" \
    "$REPO/scripts/apply_patches.sh" >"$CONFLICT_OUT" 2>&1; then
    CONFLICT_RC=0
else
    CONFLICT_RC=$?
fi
if [ "$CONFLICT_RC" -eq 1 ]; then
    pass "conflict exits 1"
else
    fail "conflict exits 1" "exit $CONFLICT_RC" "$(cat "$CONFLICT_OUT")"
fi
contains "conflict names the queue" "$CONFLICT_OUT" "Android patch queue could not be replayed"
contains "conflict names the failed patch and position" "$CONFLICT_OUT" \
    "failed patch : 0001-change-beta.patch  (1 of 1, after 0 applied)"
contains "conflict names the pin" "$CONFLICT_OUT" "upstream pin : $NEW"
contains "conflict lists the file" "$CONFLICT_OUT" "Conflicted files:"
contains "conflict lists src.txt" "$CONFLICT_OUT" "  src.txt"
contains "conflict lists other.txt" "$CONFLICT_OUT" "  other.txt"
contains "conflict shows the markers" "$CONFLICT_OUT" "<<<<<<<"
contains "conflict shows the other side" "$CONFLICT_OUT" ">>>>>>>"
BASE_BLOB_SRC="$(g -C "$UPSTREAM" rev-parse "$OLD:src.txt")"
BASE_BLOB_OTHER="$(g -C "$UPSTREAM" rev-parse "$OLD:other.txt")"
contains "conflict names the src.txt base blob" "$CONFLICT_OUT" \
    "patch base for src.txt: blob $BASE_BLOB_SRC"
contains "conflict names the other.txt base blob" "$CONFLICT_OUT" \
    "patch base for other.txt: blob $BASE_BLOB_OTHER"
contains "conflict names the patch base commit" "$CONFLICT_OUT" "commit $OLD"
contains "conflict shows the src.txt diff heading" "$CONFLICT_OUT" \
    "upstream changes to src.txt since the patch base"
contains "conflict shows the other.txt diff heading" "$CONFLICT_OUT" \
    "upstream changes to other.txt since the patch base"
contains "conflict shows upstream's side in the src.txt diff" "$CONFLICT_OUT" "+beta-upstream"
contains "conflict shows upstream's side in the other.txt diff" "$CONFLICT_OUT" "+two-upstream"
contains "conflict writes the job summary" "$CONFLICT_SUMMARY" \
    "Android patch queue could not be replayed"

echo "== record_patched_tree.sh: git am still open =="
AM_RECORD="$WORK/am-record.sha256"
if env BEPINEX_PIN_FILE="$PIN_FILE" BEPINEX_SRC="$SCRATCH" \
    BEPINEX_PATCH_GLOB="$QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$AM_RECORD" \
    "$REPO/scripts/record_patched_tree.sh" >"$WORK/am-record.out" 2>&1; then
    AM_RECORD_RC=0
else
    AM_RECORD_RC=$?
fi
if [ "$AM_RECORD_RC" -eq 1 ]; then
    pass "record refuses an open git am session"
else
    fail "record refuses an open git am session" "exit $AM_RECORD_RC" "$(cat "$WORK/am-record.out")"
fi
contains "record names the open git am session" "$WORK/am-record.out" \
    "git am is still in progress"
if [ -e "$AM_RECORD" ]; then
    fail "record writes no hash for an open git am session" "$(cat "$AM_RECORD")"
else
    pass "record writes no hash for an open git am session"
fi

echo "== report_patch_conflict.sh: unknown base =="
UNKNOWN_QUEUE="$WORK/unknown-queue"
mkdir -p "$UNKNOWN_QUEUE"
cat > "$UNKNOWN_QUEUE/0001-unknown-base.patch" <<'EOF'
From 1111111111111111111111111111111111111111 Mon Sep 17 00:00:00 2001
From: fixture <fixture@localhost>
Date: Wed, 21 Oct 2026 00:00:00 +0000
Subject: [PATCH] unknown base

---
 src.txt | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)

diff --git a/src.txt b/src.txt
index 1111111111111111111111111111111111111111..2222222222222222222222222222222222222222 100644
--- a/src.txt
+++ b/src.txt
@@ -1,3 +1,3 @@
 alpha
-beta-upstream
+beta-unknown
 gamma
-- 
2.53.0
EOF
UNKNOWN_SCRATCH="$WORK/scratch-unknown"
g clone -q "$UPSTREAM" "$UNKNOWN_SCRATCH"
g -C "$UNKNOWN_SCRATCH" checkout -q "$NEW"
UNKNOWN_OUT="$WORK/unknown.out"
UNKNOWN_SUMMARY="$WORK/unknown-summary"
: > "$UNKNOWN_SUMMARY"
if env BEPINEX_PIN_FILE="$PIN_FILE" BEPINEX_SRC="$UNKNOWN_SCRATCH" \
    BEPINEX_PATCH_GLOB="$UNKNOWN_QUEUE/*.patch" \
    GITHUB_STEP_SUMMARY="$UNKNOWN_SUMMARY" \
    "$REPO/scripts/report_patch_conflict.sh" \
    "$UNKNOWN_QUEUE/0001-unknown-base.patch" 0 >"$UNKNOWN_OUT" 2>&1; then
    UNKNOWN_RC=0
else
    UNKNOWN_RC=$?
fi
if [ "$UNKNOWN_RC" -eq 0 ]; then
    pass "unknown base is a normal diagnosis (exit 0)"
else
    fail "unknown base is a normal diagnosis (exit 0)" "exit $UNKNOWN_RC" "$(cat "$UNKNOWN_OUT")"
fi
contains "unknown base names the missing blob" "$UNKNOWN_OUT" \
    "patch base for src.txt: unknown (blob 1111111111111111111111111111111111111111 is not in this clone)"
contains "unknown base keeps the recent-commit evidence" "$UNKNOWN_OUT" \
    "base unknown; recent commits touching this file:"
if grep -Fq "fully contained in origin/master" "$UNKNOWN_OUT"; then
    fail "unknown base does not claim the pin's history is complete"
else
    pass "unknown base does not claim the pin's history is complete"
fi
contains "unknown base writes the job summary" "$UNKNOWN_SUMMARY" \
    "Android patch queue could not be replayed"

if env BEPINEX_PIN_FILE="$PIN_FILE" BEPINEX_SRC="$WORK/no-such-tree" \
    "$REPO/scripts/report_patch_conflict.sh" \
    "$UNKNOWN_QUEUE/0001-unknown-base.patch" 0 >"$WORK/no-tree.out" 2>&1; then
    NO_TREE_RC=0
else
    NO_TREE_RC=$?
fi
if [ "$NO_TREE_RC" -eq 1 ]; then
    pass "missing scratch tree exits 1"
else
    fail "missing scratch tree exits 1" "exit $NO_TREE_RC" "$(cat "$WORK/no-tree.out")"
fi
contains "missing scratch tree says what is missing" "$WORK/no-tree.out" \
    "no patched source tree"

echo "== record_patched_tree.sh: partial replay =="
PARTIAL_QUEUE="$WORK/partial-queue"
mkdir -p "$PARTIAL_QUEUE"
PARTGEN="$WORK/partgen"
g clone -q "$UPSTREAM" "$PARTGEN"
g -C "$PARTGEN" checkout -q "$OLD"
sed -i 's/^alpha$/alpha-patched/' "$PARTGEN/src.txt"
g -C "$PARTGEN" commit -qam "port: change alpha"
g -C "$PARTGEN" format-patch -1 HEAD --stdout > "$PARTIAL_QUEUE/0001-alpha.patch"
sed -i 's/^gamma$/gamma-patched/' "$PARTGEN/src.txt"
g -C "$PARTGEN" commit -qam "port: change gamma"
g -C "$PARTGEN" format-patch -1 HEAD --stdout > "$PARTIAL_QUEUE/0002-gamma.patch"

PARTIAL_PINREPO="$WORK/pinrepo-partial"
mkdir -p "$PARTIAL_PINREPO/provenance"
g -C "$PARTIAL_PINREPO" init -q
write_pin_to "$PARTIAL_PINREPO" "$OLD" "$OLD_DESCRIBE"
g -C "$PARTIAL_PINREPO" add provenance/bepinex.env
g -C "$PARTIAL_PINREPO" commit -qm "pin at old"
PARTIAL_PIN_FILE="$PARTIAL_PINREPO/provenance/bepinex.env"

PARTIAL_SCRATCH="$WORK/scratch-partial"
PARTIAL_RECORD="$WORK/partial-tree.sha256"
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$PARTIAL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/0001-*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$PARTIAL_RECORD" BEPINEX_SKIP_TREE_GATE=1 \
    "$REPO/scripts/apply_patches.sh" >"$WORK/partial-apply.out" 2>&1; then
    PARTIAL_APPLY_RC=0
else
    PARTIAL_APPLY_RC=$?
fi
if [ "$PARTIAL_APPLY_RC" -eq 0 ]; then
    pass "a one-patch prefix applies"
else
    fail "a one-patch prefix applies" "exit $PARTIAL_APPLY_RC" "$(cat "$WORK/partial-apply.out")"
fi
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$PARTIAL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$PARTIAL_RECORD" \
    "$REPO/scripts/record_patched_tree.sh" >"$WORK/partial-record.out" 2>&1; then
    PARTIAL_RECORD_RC=0
else
    PARTIAL_RECORD_RC=$?
fi
if [ "$PARTIAL_RECORD_RC" -eq 1 ]; then
    pass "record refuses a prefix of the queue"
else
    fail "record refuses a prefix of the queue" "exit $PARTIAL_RECORD_RC" "$(cat "$WORK/partial-record.out")"
fi
contains "record names the applied count" "$WORK/partial-record.out" \
    "partial replay (1 of 2 patches applied)"
if [ -e "$PARTIAL_RECORD" ]; then
    fail "record writes no hash for a prefix" "$(cat "$PARTIAL_RECORD")"
else
    pass "record writes no hash for a prefix"
fi

echo "== record_patched_tree.sh: complete replay =="
FULL_SCRATCH="$WORK/scratch-full"
FULL_RECORD="$WORK/full-tree.sha256"
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$FULL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$FULL_RECORD" BEPINEX_SKIP_TREE_GATE=1 \
    "$REPO/scripts/apply_patches.sh" >"$WORK/full-apply.out" 2>&1; then
    FULL_APPLY_RC=0
else
    FULL_APPLY_RC=$?
fi
if [ "$FULL_APPLY_RC" -eq 0 ]; then
    pass "the complete queue applies"
else
    fail "the complete queue applies" "exit $FULL_APPLY_RC" "$(cat "$WORK/full-apply.out")"
fi
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$FULL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$FULL_RECORD" \
    "$REPO/scripts/record_patched_tree.sh" >"$WORK/full-record.out" 2>&1; then
    FULL_RECORD_RC=0
else
    FULL_RECORD_RC=$?
fi
if [ "$FULL_RECORD_RC" -eq 0 ]; then
    pass "record accepts the complete queue"
else
    fail "record accepts the complete queue" "exit $FULL_RECORD_RC" "$(cat "$WORK/full-record.out")"
fi
EXPECTED_TREE="$(g -C "$FULL_SCRATCH" rev-parse "HEAD^{tree}")"
if [ "$(cat "$FULL_RECORD" 2>/dev/null || true)" = "$EXPECTED_TREE" ]; then
    pass "record writes the complete tree hash"
else
    fail "record writes the complete tree hash" \
        "expected $EXPECTED_TREE" "got $(cat "$FULL_RECORD" 2>/dev/null || true)"
fi
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$FULL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$FULL_RECORD" \
    "$REPO/scripts/apply_patches.sh" >"$WORK/full-gate.out" 2>&1; then
    FULL_GATE_RC=0
else
    FULL_GATE_RC=$?
fi
if [ "$FULL_GATE_RC" -eq 0 ]; then
    pass "the recorded hash passes the gate on the next replay"
else
    fail "the recorded hash passes the gate on the next replay" "exit $FULL_GATE_RC" "$(cat "$WORK/full-gate.out")"
fi
contains "the next replay reports the match" "$WORK/full-gate.out" \
    "patched tree matches the recorded hash"

echo "== record_patched_tree.sh: dirty tree =="
FULL_HASH_BEFORE="$(cat "$FULL_RECORD")"
touch "$FULL_SCRATCH/stray.bin"
if env BEPINEX_PIN_FILE="$PARTIAL_PIN_FILE" BEPINEX_SRC="$FULL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$PARTIAL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$FULL_RECORD" \
    "$REPO/scripts/record_patched_tree.sh" >"$WORK/dirty-record.out" 2>&1; then
    DIRTY_RECORD_RC=0
else
    DIRTY_RECORD_RC=$?
fi
if [ "$DIRTY_RECORD_RC" -eq 1 ]; then
    pass "record refuses a dirty tree"
else
    fail "record refuses a dirty tree" "exit $DIRTY_RECORD_RC" "$(cat "$WORK/dirty-record.out")"
fi
contains "record names the dirty tree" "$WORK/dirty-record.out" \
    "patched tree is not clean"
if [ "$(cat "$FULL_RECORD")" = "$FULL_HASH_BEFORE" ]; then
    pass "record leaves the hash unchanged for a dirty tree"
else
    fail "record leaves the hash unchanged for a dirty tree" \
        "before $FULL_HASH_BEFORE" "after $(cat "$FULL_RECORD")"
fi
rm -f "$FULL_SCRATCH/stray.bin"

echo "== deletion patch: stale deletion fails, rebaselined deletion records =="
# Upstream edits a file the port removes. A deletion patch generated at the old
# pin cannot be carried across that move: the file's content changed on the
# upstream side while the patch still wants it gone. The queue must fail, and
# the recovery is to regenerate the deletion at the new pin, replay the whole
# queue, and only then record its tree.
DEL_UPSTREAM="$WORK/deletion-upstream"
mkdir -p "$DEL_UPSTREAM"
g -C "$DEL_UPSTREAM" init -q
printf 'desktop launcher\nconfiguration line\n' > "$DEL_UPSTREAM/desktop.txt"
printf 'shared\n' > "$DEL_UPSTREAM/shared.txt"
g -C "$DEL_UPSTREAM" add desktop.txt shared.txt
g -C "$DEL_UPSTREAM" commit -qm "deletion upstream base"
DEL_OLD="$(g -C "$DEL_UPSTREAM" rev-parse HEAD)"
DEL_OLD_DESCRIBE="$(g -C "$DEL_UPSTREAM" describe --tags --always "$DEL_OLD")"

DEL_QUEUE="$WORK/deletion-queue"
mkdir -p "$DEL_QUEUE"
DEL_PATCHGEN="$WORK/deletion-patchgen"
g clone -q "$DEL_UPSTREAM" "$DEL_PATCHGEN"
g -C "$DEL_PATCHGEN" checkout -q "$DEL_OLD"
rm "$DEL_PATCHGEN/desktop.txt"
g -C "$DEL_PATCHGEN" commit -qam "port: remove desktop launcher"
g -C "$DEL_PATCHGEN" format-patch -1 HEAD --stdout > "$DEL_QUEUE/0001-remove-desktop.patch"
sed -i 's/^shared$/shared-patched/' "$DEL_PATCHGEN/shared.txt"
g -C "$DEL_PATCHGEN" commit -qam "port: adapt shared"
g -C "$DEL_PATCHGEN" format-patch -1 HEAD --stdout > "$DEL_QUEUE/0002-adapt-shared.patch"

sed -i 's/^configuration line$/configuration line, revised upstream/' "$DEL_UPSTREAM/desktop.txt"
g -C "$DEL_UPSTREAM" commit -qam "upstream: revise desktop launcher"
DEL_NEW="$(g -C "$DEL_UPSTREAM" rev-parse HEAD)"
DEL_NEW_DESCRIBE="$(g -C "$DEL_UPSTREAM" describe --tags --always "$DEL_NEW")"

DEL_PINREPO="$WORK/deletion-pinrepo"
mkdir -p "$DEL_PINREPO/provenance"
g -C "$DEL_PINREPO" init -q
write_pin_to "$DEL_PINREPO" "$DEL_NEW" "$DEL_NEW_DESCRIBE" "$DEL_UPSTREAM"
g -C "$DEL_PINREPO" add provenance/bepinex.env
g -C "$DEL_PINREPO" commit -qm "pin deletion case at new"
DEL_PIN_FILE="$DEL_PINREPO/provenance/bepinex.env"

DEL_STALE_SCRATCH="$WORK/deletion-stale-scratch"
if env BEPINEX_PIN_FILE="$DEL_PIN_FILE" BEPINEX_SRC="$DEL_STALE_SCRATCH" \
    BEPINEX_PATCH_GLOB="$DEL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$WORK/deletion-stale-tree.sha256" \
    BEPINEX_SKIP_TREE_GATE=1 \
    "$REPO/scripts/apply_patches.sh" >"$WORK/deletion-stale.out" 2>&1; then
    DEL_STALE_RC=0
else
    DEL_STALE_RC=$?
fi
if [ "$DEL_STALE_RC" -eq 1 ]; then
    pass "stale deletion patch fails on the moved pin"
else
    fail "stale deletion patch fails on the moved pin" \
        "exit $DEL_STALE_RC" "$(cat "$WORK/deletion-stale.out")"
fi
contains "stale deletion failure names the queue" "$WORK/deletion-stale.out" \
    "Android patch queue could not be replayed"
contains "stale deletion failure names the failed patch" "$WORK/deletion-stale.out" \
    "0001-remove-desktop.patch"
contains "stale deletion failure names the deleted file" "$WORK/deletion-stale.out" \
    "desktop.txt"

# Rebaseline only the deletion, at the new pin, while keeping the rest of the
# queue intact. The complete queue must then replay and record as one unit.
DEL_REBASEGEN="$WORK/deletion-rebasegen"
g clone -q "$DEL_UPSTREAM" "$DEL_REBASEGEN"
g -C "$DEL_REBASEGEN" checkout -q "$DEL_NEW"
rm "$DEL_REBASEGEN/desktop.txt"
g -C "$DEL_REBASEGEN" commit -qam "port: remove desktop launcher (rebaselined)"
g -C "$DEL_REBASEGEN" format-patch -1 HEAD --stdout > "$DEL_QUEUE/0001-remove-desktop.patch"

DEL_SCRATCH="$WORK/deletion-scratch"
DEL_RECORD="$WORK/deletion-tree.sha256"
if env BEPINEX_PIN_FILE="$DEL_PIN_FILE" BEPINEX_SRC="$DEL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$DEL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$DEL_RECORD" BEPINEX_SKIP_TREE_GATE=1 \
    "$REPO/scripts/apply_patches.sh" >"$WORK/deletion-apply.out" 2>&1; then
    DEL_APPLY_RC=0
else
    DEL_APPLY_RC=$?
fi
if [ "$DEL_APPLY_RC" -eq 0 ]; then
    pass "rebaselined deletion patch applies"
else
    fail "rebaselined deletion patch applies" \
        "exit $DEL_APPLY_RC" "$(cat "$WORK/deletion-apply.out")"
fi
if [ ! -e "$DEL_SCRATCH/desktop.txt" ]; then
    pass "the replayed tree no longer has the deleted file"
else
    fail "the replayed tree no longer has the deleted file"
fi
if env BEPINEX_PIN_FILE="$DEL_PIN_FILE" BEPINEX_SRC="$DEL_SCRATCH" \
    BEPINEX_PATCH_GLOB="$DEL_QUEUE/*.patch" \
    BEPINEX_PATCHED_TREE_FILE="$DEL_RECORD" \
    "$REPO/scripts/record_patched_tree.sh" >"$WORK/deletion-record.out" 2>&1; then
    DEL_RECORD_RC=0
else
    DEL_RECORD_RC=$?
fi
if [ "$DEL_RECORD_RC" -eq 0 ]; then
    pass "record accepts the complete rebaselined queue"
else
    fail "record accepts the complete rebaselined queue" \
        "exit $DEL_RECORD_RC" "$(cat "$WORK/deletion-record.out")"
fi
DEL_EXPECTED_TREE="$(g -C "$DEL_SCRATCH" rev-parse "HEAD^{tree}")"
if [ "$(cat "$DEL_RECORD" 2>/dev/null || true)" = "$DEL_EXPECTED_TREE" ]; then
    pass "record writes the rebaselined replay tree"
else
    fail "record writes the rebaselined replay tree" \
        "expected $DEL_EXPECTED_TREE" "got $(cat "$DEL_RECORD" 2>/dev/null || true)"
fi

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
