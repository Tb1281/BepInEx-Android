#!/usr/bin/env bash
# Checks the 16KB page-alignment logic used by the CI steps.
#
# The logic under test is "given the p_align values readelf reports for LOAD
# segments, does the step decide correctly", so readelf is stubbed and the LOAD
# lines are scripted. That keeps every case deterministic, including the one that
# cannot be built from the committed binaries: all of them are already 16KB
# aligned, so the failure direction has no real example to point at.
#
# All cases run under `bash -e`, because that is how a GitHub `run:` block
# executes and because the defect is invisible without errexit - an earlier
# version of this harness omitted it and reported the broken form as passing.
#
# The first two cases test `shipped`, the form that used to be written into
# fetch-runtime.yml's YAML. It is kept here as a pinned record of the defect that
# motivated the shared script, not because anything still uses it: the check now
# lives in scripts/check_elf_alignment.sh, which the later cases exercise
# directly. Testing YAML by copy-pasting it into a fixture is how a third copy
# of the logic ended up in build-native-deps.yml unnoticed.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/align-check.XXXXXX")"
export WORK          # the forms run under a fresh `bash -e`, which inherits only exports
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"

# --- stub readelf: prints the LOAD lines named by ALIGN_STUB, one per call ----
cat > "$WORK/bin/readelf" <<'STUB'
#!/usr/bin/env bash
if [ "${ALIGN_STUB_MODE:-loads}" = "none" ]; then
    exit 0
fi
n=0
while IFS= read -r line; do
    n=$((n + 1))
    printf '  LOAD           0x%08x 0x%08x 0x%08x 0x%08x  R E   0x%08x %10s %s\n' \
        "$((0x1000 * n))" 0x1000 0x1000 0x1000 "$line" 0x1000 "0x$(printf '%x' "$line")"
done <<<"$ALIGN_STUB"
STUB
chmod +x "$WORK/bin/readelf"

# --- the form that shipped in fetch-runtime.yml ------------------------------
# Both forms are invoked indirectly, through `declare -f` inside a fresh
# `bash -e`, so that the runner's errexit applies to them. The subshell-scoped
# PATH/ALIGN_STUB warnings that follow are the harness working as intended.
# shellcheck disable=SC2329,SC2030
shipped() {
    ( cd "$WORK" && export PATH="$WORK/bin:$PATH" ALIGN_STUB="$1"
      FAIL=0
      for so in ./lib*.so; do
        [ -f "$so" ] || continue
        bad="$(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }' | while read -r a; do
          [ "$((a))" -lt 16384 ] && echo "$a"
        done)"
        if [ -n "$bad" ]; then
          echo "UNALIGNED: $so PT_LOAD p_align=$bad"
          FAIL=1
        fi
      done
      test "$FAIL" = 0 )
}

# --- the shipped script, exercised as a black box ----------------------------
# Invoked through the real path, so a change that breaks its argument handling
# or its exit status is caught here rather than in a workflow run.
# REPO is exported because run_form executes each form in a fresh `bash -e`,
# which inherits exported variables only.
shared() {
    ( cd "$WORK" && PATH="$WORK/bin:$PATH" ALIGN_STUB="${ALIGN_STUB:-}" \
      ALIGN_STUB_MODE="${ALIGN_STUB_MODE:-loads}" \
      "$REPO/scripts/check_elf_alignment.sh" "$WORK/lib" )
}

# Every case runs through `bash -e`, matching the runner.
run_form() { bash -e -c "$(declare -f "$1"); $1 \"\${ALIGN_STUB}\"" 2>&1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO

fails=0
expect() { # expect <want-exit> <label> <fn> <loads> [needle]
    local want="$1" label="$2" fn="$3" loads="$4"
    shift 4
    local out rc needle
    out="$(ALIGN_STUB="$loads" run_form "$fn")"; rc=$?
    local ok=1
    [ "$rc" -eq "$want" ] || ok=0
    for needle in "$@"; do
        printf '%s' "$out" | grep -qF -- "$needle" || ok=0
    done
    if [ "$ok" = 1 ]; then
        echo "ok    $label (exit $rc)"
    else
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        output: /'
        fails=$((fails + 1))
    fi
}

: > "$WORK/libdummy.so"
mkdir -p "$WORK/lib"
: > "$WORK/lib/libdummy.so"   # only the readelf output matters

echo "== 16KB alignment check (run under bash -e, as the runner does) =="
echo
ALIGNED=$'0x4000\n0x4000\n0x4000'      # every segment 16KB aligned
MIXED=$'0x1000\n0x4000\n0x4000'        # first segment 4KB, last two aligned
LASTBAD=$'0x4000\n0x1000'              # last segment is the bad one

echo "-- the form that shipped, asserted against its observed-broken behaviour"
# Both cases exit 1. The misaligned one is right for the wrong reason (errexit
# fired before the condition was evaluated); the aligned one is the defect, and
# neither printed anything. Pinning them here means a reintroduction is caught.
expect 1 "shipped rejects a 4KB-aligned library (silently)" shipped "$MIXED"
expect 1 "shipped REJECTS an aligned library - the defect"     shipped "$ALIGNED"
echo
echo "-- scripts/check_elf_alignment.sh, the one implementation now"
expect 0 "shared accepts an aligned library"        shared "$ALIGNED"
expect 1 "shared rejects a 4KB-aligned library"     shared "$MIXED" UNALIGNED
expect 1 "shared rejects when only the last is bad" shared "$LASTBAD" UNALIGNED

# A file readelf cannot describe must not read as aligned: "nothing was
# unaligned" and "nothing was checked" are different states, and conflating them
# is a fail-open on the one input a checker cannot evaluate.
out="$(ALIGN_STUB_MODE=none ALIGN_STUB="" run_form shared)"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q UNKNOWN; then
    echo "ok    shared refuses a file with no LOAD segment (exit $rc)"
else
    echo "FAIL  shared accepted an unreadable ELF: expected exit 1 + UNKNOWN, got $rc"
    printf '%s\n' "$out" | sed 's/^/        output: /'
    fails=$((fails + 1))
fi

# Empty input is not a pass either: a caller that renamed its output directory
# would otherwise get a green step that inspected nothing.
out="$(bash -e "$REPO/scripts/check_elf_alignment.sh" "$WORK/empty" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ]; then
    echo "ok    shared refuses an empty directory (exit $rc)"
else
    echo "FAIL  shared accepted an empty directory"
    fails=$((fails + 1))
fi

out="$(bash -e "$REPO/scripts/check_elf_alignment.sh" 2>&1)"; rc=$?
if [ "$rc" -eq 2 ]; then
    echo "ok    shared refuses to run with no arguments (exit $rc)"
else
    echo "FAIL  shared accepted an empty argument list (exit $rc)"
    fails=$((fails + 1))
fi

echo
echo "On an aligned library the shipped form's last '[ ... ] && echo' short-circuits"
echo "to a non-zero status, errexit kills the step, and it exits 1 printing nothing"
echo "- so an aligned library and a misaligned one were indistinguishable from the"
echo "run log. fetch-runtime executes weekly and by hand, which is why this went"
echo "unnoticed: the symptom reads as a runner fault, not an inverted condition."
echo
if [ "$fails" -eq 0 ]; then echo "PASS"; else echo "$fails CHECK(S) FAILED"; fi
exit "$fails"