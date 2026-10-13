#!/usr/bin/env bash
# Reproduces and pins the `sort | head` SIGPIPE failure.
#
# scripts/package_bepinex_android.sh picks the newest build output with
# `find -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2-`, under
# `set -euo pipefail`. `head` exits after one line and closes the pipe; if `sort`
# has not finished writing, it takes SIGPIPE, reports "write error", exits 2, and
# pipefail turns that into a failed step. Whether it happens depends on how much
# `sort` still has buffered when `head` leaves - so the failure is intermittent,
# which is why a build that packaged correctly yesterday can fail today with
# nothing in the change to explain it.
#
# Both forms are run many times over a large input, because a single run proves
# nothing about a race.
set -uo pipefail

WORK="$(mktemp -d "${TMPDIR:-/tmp}/sigpipe.XXXXXX")"
export WORK          # trial() runs each form under a fresh `bash -c`, which inherits only exports
trap 'rm -rf "$WORK"' EXIT

# Enough lines that sort still has buffered output when head exits.
python3 - "$WORK/big.txt" <<'PY'
import random, sys
with open(sys.argv[1], "w") as f:
    for i in range(20000):
        f.write(f"{random.random():.6f} ./sub/dir/file{i:05d}.cs\n")
PY
echo "input lines: $(wc -l < "$WORK/big.txt")"

# head_form / sed_form: identical pipelines, differing only in the final reader.
# Invoked indirectly, through `declare -f` inside a fresh `bash -c`.
# shellcheck disable=SC2329
head_form() { sort -rn < "$WORK/big.txt" | head -1; }
# shellcheck disable=SC2329
sed_form()  { sort -rn < "$WORK/big.txt" | sed -n '1p'; }

trial() { # trial <fn> <iterations> -> prints failure count, keeps the last stderr
    local fn="$1" n="$2" fails=0
    for _ in $(seq 1 "$n"); do
        if ! bash -c "set -euo pipefail; $(declare -f "$fn"); $fn" >/dev/null 2>"$WORK/err"; then
            fails=$((fails + 1))
            cp "$WORK/err" "$WORK/last_err" 2>/dev/null || true
        fi
    done
    printf '%s' "$fails"
}

TRIALS=40
h="$(trial head_form "$TRIALS")"
s="$(trial sed_form "$TRIALS")"

echo ""
echo "  sort -rn | head -1        failed ${h}/${TRIALS}"
echo "  sort -rn | sed -n '1p'    failed ${s}/${TRIALS}"

echo ""
echo "equivalence on the same input:"
a="$(sort -rn < "$WORK/big.txt" | head -1 2>/dev/null || true)"
b="$(sort -rn < "$WORK/big.txt" | sed -n '1p')"
echo "  head -1 : $a"
echo "  sed 1p : $b"
if [ "$a" = "$b" ]; then echo "  identical first line"; else echo "  DIFFERENT"; exit 1; fi

echo ""
if [ "$h" -gt 0 ] && [ "$s" -eq 0 ]; then
    echo "CONFIRMED: the head form fails intermittently and the sed form does not."
    echo "sed -n '1p' reads the whole stream before exiting, so sort never sees a"
    echo "closed pipe. It selects the same line, so only the failure goes away."
    exit 0
fi
if [ "$h" -eq 0 ]; then
    echo "INCONCLUSIVE: the head form did not fail in ${TRIALS} trials."
    echo "The race is timing-dependent; raise TRIALS or the input size to observe it."
    exit 1
fi
echo "UNEXPECTED: the replacement form also failed (${s}/${TRIALS})."
echo "last stderr from that form:"
sed 's/^/        /' "$WORK/last_err" 2>/dev/null || echo "        (none captured)"
exit 1