#!/usr/bin/env bash
# Fixture for scripts/verify_apk_entries.sh.
#
# Incremental Gradle builds packaged duplicate local entries twice in one
# session (958 vs 475, then 478 vs 475) — working APKs carrying dead weight,
# caught only by counting. This pins the gate: a clean APK passes, a doubled
# one fails and names the shape, a missing file fails rather than passing
# vacuously.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/apkentries.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
GATE="$REPO/scripts/verify_apk_entries.sh"

fails=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }

echo "== verify_apk_entries.sh =="

# 1. a well-formed zip passes
python3 - "$FIX/good.zip" <<'PY' >/dev/null 2>&1
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_DEFLATED) as z:
    z.writestr("a.txt", b"aaa")
    z.writestr("b/c.txt", b"bbb")
PY
if "$GATE" "$FIX/good.zip" >/dev/null 2>&1; then
    ok "well-formed archive passes"
else
    fail "well-formed archive failed"
fi

# 2. a doubled archive (local headers appended twice, central written once —
#    the exact incremental-build shape) fails and says so.
python3 - "$FIX/good.zip" "$FIX/doubled.zip" <<'PY' >/dev/null 2>&1
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
raw = open(src, "rb").read()
# Body is local headers ONLY: walk them to find where the central directory
# begins. (Slicing at the EOCD would drag the central directory into the
# "body" and duplicate that too — which is how this fixture first passed
# against its own gate.)
import struct
pos = 0
while pos + 30 <= len(raw):
    if struct.unpack("<I", raw[pos:pos + 4])[0] != 0x04034b50:
        break
    fn, ef = struct.unpack("<HH", raw[pos + 26:pos + 30])
    comp, = struct.unpack("<I", raw[pos + 18:pos + 22])
    pos = pos + 30 + fn + ef + comp
body = raw[:pos]
infos = zipfile.ZipFile(src).infolist()
with open(dst, "wb") as fp:
    fp.write(body)
    fp.write(body)  # duplicate every local entry
    # central records point at the FIRST copies; sizes/CRCs are intentionally
    # unwritten here — the gate only compares names, counts and duplicates.
    off = 0
    central_records = b""
    pos = 0
    while pos + 30 <= len(body):
        if struct.unpack("<I", body[pos:pos+4])[0] != 0x04034B50:
            break
        fn, ef = struct.unpack("<HH", body[pos+26:pos+30])
        comp, = struct.unpack("<I", body[pos+18:pos+22])
        name = body[pos+30:pos+30+fn]
        # central file header (names/counts only; sizes left zero)
        central_records += struct.pack("<IHHHHHHIIIHHHHHII",
            0x02014b50, 20, 20, 0, 8, 0, 0, 0, 0, 0, len(name),
            0, 0, 0, 0, 0, off)
        central_records += name
        off = pos + 30 + fn + ef + comp
        pos = off
    start = off * 2
    fp.write(central_records)
    fp.write(struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, len(infos), len(infos),
                         len(central_records), start, 0))
PY
out="$("$GATE" "$FIX/doubled.zip" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q "local entries"; then
    ok "doubled archive fails and names the count mismatch"
else
    fail "doubled archive exited $rc: $out"
fi

# 3. a missing file fails, not passes
if "$GATE" "$FIX/no-such.apk" >/dev/null 2>&1; then
    fail "missing file exited 0 (fail-open)"
else
    ok "missing file fails closed"
fi

# 4. the real shipping APK on disk passes (when present)
if [ -f "$REPO/launcher/app/build/outputs/apk/debug/app-debug.apk" ]; then
    if "$GATE" "$REPO/launcher/app/build/outputs/apk/debug/app-debug.apk" >/dev/null 2>&1; then
        ok "current build output passes"
    else
        fail "current build output fails the gate"
    fi
else
    echo "skip  no built APK on disk"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
