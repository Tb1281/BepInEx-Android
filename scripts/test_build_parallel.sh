#!/usr/bin/env bash
# Fixture for scripts/build_packages_parallel.sh.
#
# The parallel step exists to save wall time without weakening the "both
# package sets present before restore" guarantee. The cases that matter are
# the quiet ones: a failure in one build masked by the success of the other,
# logs interleaved past readability, and a "parallel" step that actually runs
# serially. Fast stubs stand in for the real dotnet builds via the helper's
# positional args.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/parpkg.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$REPO/scripts/build_packages_parallel.sh"

fails=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }

echo "== build_packages_parallel.sh =="

stub() { # stub <path> <seconds> <exit> <marker-line>
    cat > "$1" <<STUB
#!/usr/bin/env bash
sleep $2
echo "$4"
exit $3
STUB
    chmod +x "$1"
}

# 1. both succeed -> exit 0, both outputs present in fixed serial order
stub "$FIX/a.sh" 1 0 "AAA-DONE"
stub "$FIX/b.sh" 1 0 "BBB-DONE"
out="$("$HELPER" "$FIX/a.sh" "$FIX/b.sh" 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "AAA-DONE" \
&& printf '%s' "$out" | grep -q "BBB-DONE" \
&& printf '%s' "$out" | grep -q "both package sets built"; then
    # Serial order: AAA section precedes BBB section.
    a_line="$(printf '%s\n' "$out" | grep -n "AAA-DONE" | cut -d: -f1)"
    b_line="$(printf '%s\n' "$out" | grep -n "BBB-DONE" | cut -d: -f1)"
    if [ "$a_line" -lt "$b_line" ]; then
        ok "both succeed, logs complete in fixed serial order"
    else
        fail "log order not serial"
    fi
else
    fail "both-succeed case exited $rc"
fi

# 2. one fails -> exit 1, names the failed script, still waits for the other
stub "$FIX/good.sh" 2 0 "GOOD-DONE"
stub "$FIX/bad.sh" 1 1 "BAD-DONE"
out="$("$HELPER" "$FIX/good.sh" "$FIX/bad.sh" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "FAILED: $FIX/bad.sh" \
&& printf '%s' "$out" | grep -q "GOOD-DONE"; then
    ok "one failure fails closed, names it, and still waits for the other"
else
    fail "one-failure case exited $rc without naming bad.sh"
fi

# 3. both fail -> exit 1, names both
stub "$FIX/bad1.sh" 1 2 "BAD1"
stub "$FIX/bad2.sh" 1 3 "BAD2"
out="$("$HELPER" "$FIX/bad1.sh" "$FIX/bad2.sh" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "FAILED: $FIX/bad1.sh" \
&& printf '%s' "$out" | grep -qF "FAILED: $FIX/bad2.sh"; then
    ok "two failures name both scripts"
else
    fail "two-failure case exited $rc"
fi

# 4. actually parallel: two 5 s stubs finish well under serial time.
#    Bounds are generous (parallel ~5 s, serial 10 s+) so loaded runners
#    cannot flake this; a serial implementation cannot pass it.
stub "$FIX/s1.sh" 5 0 "S1"
stub "$FIX/s2.sh" 5 0 "S2"
start="$(date +%s)"
"$HELPER" "$FIX/s1.sh" "$FIX/s2.sh" >/dev/null 2>&1; rc=$?
elapsed=$(( $(date +%s) - start ))
if [ "$rc" -eq 0 ] && [ "$elapsed" -lt 9 ]; then
    ok "two 5 s builds overlap (${elapsed} s < 9 s)"
else
    fail "no overlap: ${elapsed} s, exit $rc"
fi

# 5. missing script fails immediately, before running anything
out="$("$HELPER" "$FIX/s1.sh" "$FIX/no-such.sh" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "not executable"; then
    ok "missing script fails closed without running"
else
    fail "missing script exited $rc"
fi

# 6. isolated TMPDIR per build: two concurrent dotnet processes sharing
#    $TMPDIR race on $TMPDIR/.dotnet/shm (EEXIST on mkdir). Each build must
#    see a distinct TMPDIR.
cat > "$FIX/tmp-a.sh" <<'STUB'
#!/usr/bin/env bash
echo "TMPDIR=$TMPDIR"
STUB
cat > "$FIX/tmp-b.sh" <<'STUB'
#!/usr/bin/env bash
echo "TMPDIR=$TMPDIR"
STUB
chmod +x "$FIX/tmp-a.sh" "$FIX/tmp-b.sh"
out="$("$HELPER" "$FIX/tmp-a.sh" "$FIX/tmp-b.sh" 2>&1)"; rc=$?
tmp_a="$(printf '%s\n' "$out" | grep -m1 '^TMPDIR=' | cut -d= -f2-)"
tmp_b="$(printf '%s\n' "$out" | grep '^TMPDIR=' | tail -1 | cut -d= -f2-)"
if [ "$rc" -eq 0 ] && [ -n "$tmp_a" ] && [ -n "$tmp_b" ] \
&& [ "$tmp_a" != "$tmp_b" ] \
&& [ "$tmp_a" != "${TMPDIR:-/tmp}" ] && [ "$tmp_b" != "${TMPDIR:-/tmp}" ]; then
    ok "each build gets a distinct TMPDIR ($tmp_a vs $tmp_b)"
else
    fail "TMPDIR not isolated: a='$tmp_a' b='$tmp_b' rc=$rc"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
