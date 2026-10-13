#!/usr/bin/env bash
# Fixture for scripts/zip_deterministic.py.
#
# The shippable archives were built with `zip -qr`, which records mtimes,
# walks in filesystem order and writes explicit directory entries — so two
# runs over identical inputs produced different bytes (the 47 vs 49 entry
# divergence in docs/todo.md Phase 3 task 12). This pins the fix:
#
#   same tree + same SOURCE_DATE_EPOCH -> byte-identical archives
#   entries sorted, files only (no explicit directory entries)
#   every entry carries the epoch as its mtime
#   a missing input fails rather than writing a partial archive
#   without SOURCE_DATE_EPOCH the newest input mtime is used (stable),
#   not the wall clock
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/detzip.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
ZIP="$REPO/scripts/zip_deterministic.py"

fails=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }

echo "== zip_deterministic.py =="

# A stage with nested dirs, created in non-sorted order with skewed mtimes.
mkdir -p "$FIX/stage/config" "$FIX/stage/core/sub"
printf 'b' > "$FIX/stage/core/b.dll"
printf 'a' > "$FIX/stage/core/a.dll"
printf 'c' > "$FIX/stage/core/sub/c.dll"
printf 'cfg' > "$FIX/stage/config/BepInEx.cfg"
touch -d '2020-01-01 00:00:00' "$FIX/stage/core/a.dll"
touch -d '2024-06-01 00:00:00' "$FIX/stage/core/b.dll"
touch -d '2022-03-01 00:00:00' "$FIX/stage/core/sub/c.dll"
touch -d '2021-05-01 00:00:00' "$FIX/stage/config/BepInEx.cfg"

# 1. same tree + same epoch -> byte-identical
if SOURCE_DATE_EPOCH=1700000000 python3 "$ZIP" "$FIX/stage" "$FIX/a.zip" core config >/dev/null 2>&1 \
&& SOURCE_DATE_EPOCH=1700000000 python3 "$ZIP" "$FIX/stage" "$FIX/b.zip" core config >/dev/null 2>&1 \
&& cmp -s "$FIX/a.zip" "$FIX/b.zip"; then
    ok "same tree + same epoch are byte-identical"
else
    fail "same tree + same epoch differed"
fi

# 2. entries sorted, files only
entries="$(unzip -Z1 "$FIX/a.zip")"
if [ "$(printf '%s\n' "$entries" | LC_ALL=C sort -c 2>/dev/null; echo $?)" = 0 ] \
&& [ "$(printf '%s\n' "$entries" | grep -c .)" = 4 ] \
&& ! printf '%s\n' "$entries" | grep -q '/$'; then
    ok "4 file entries, sorted, no directory entries"
else
    fail "entry set wrong:"; printf '%s\n' "$entries" | sed 's/^/        /'
fi

# 3. every entry carries the epoch mtime (2023-11-14 from 1700000000)
if python3 - "$FIX/a.zip" <<'PY' >/dev/null 2>&1
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
infos = z.infolist()
assert infos, "empty archive"
for i in infos:
    assert i.date_time[:3] == (2023, 11, 14), (i.filename, i.date_time)
    assert (i.external_attr >> 16) == 0o644, (i.filename, oct(i.external_attr >> 16))
PY
then
    ok "every entry dated 2023-11-14 with mode 0644"
else
    fail "entry mtimes or modes are not the epoch"
fi

# 4. a missing input fails rather than writing a partial archive
rm -f "$FIX/partial.zip"
if python3 "$ZIP" "$FIX/stage" "$FIX/partial.zip" core no-such-dir >/dev/null 2>&1; then
    fail "missing input exited 0 (fail-open)"
else
    ok "missing input fails closed"
fi

# 5. without SOURCE_DATE_EPOCH the newest input mtime wins, twice identically
#    (stable for a given tree, unlike the wall clock).
if env -u SOURCE_DATE_EPOCH python3 "$ZIP" "$FIX/stage" "$FIX/c.zip" core config >/dev/null 2>&1 \
&& env -u SOURCE_DATE_EPOCH python3 "$ZIP" "$FIX/stage" "$FIX/d.zip" core config >/dev/null 2>&1 \
&& cmp -s "$FIX/c.zip" "$FIX/d.zip" \
&& python3 - "$FIX/c.zip" <<'PY' >/dev/null 2>&1
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    # newest input mtime is 2024-06-01 (b.dll)
    assert i.date_time[:3] == (2024, 6, 1), (i.filename, i.date_time)
PY
then
    ok "unset epoch falls back to newest input mtime, stably"
else
    fail "unset-epoch fallback wrong"
fi

# 6. different epochs differ (the variable actually decides the bytes).
#    100 s apart: zip timestamps have 2-second granularity, so a 1 s gap
#    would be rounded away and prove nothing.
if SOURCE_DATE_EPOCH=1700000100 python3 "$ZIP" "$FIX/stage" "$FIX/e.zip" core config >/dev/null 2>&1 \
&& ! cmp -s "$FIX/a.zip" "$FIX/e.zip" >/dev/null 2>&1; then
    ok "different epochs produce different bytes"
else
    fail "epoch change had no effect"
fi

# 7. the real BepInEx.Android.zip name-set survives a deterministic re-zip:
#    files only, nothing lost against the committed archive.
if [ -f "$REPO/launcher/app/src/main/assets/BepInEx.Android.zip" ]; then
    rm -rf "$FIX/be" && mkdir -p "$FIX/be"
    unzip -q "$REPO/launcher/app/src/main/assets/BepInEx.Android.zip" -d "$FIX/be"
    SOURCE_DATE_EPOCH=1700000000 python3 "$ZIP" "$FIX/be" "$FIX/be.zip" core config >/dev/null 2>&1
    orig="$(unzip -Z1 "$REPO/launcher/app/src/main/assets/BepInEx.Android.zip" | grep -v '/$' | LC_ALL=C sort)"
    rez="$(unzip -Z1 "$FIX/be.zip" | LC_ALL=C sort)"
    if [ "$orig" = "$rez" ]; then
        ok "re-zipped BepInEx.Android.zip loses no file"
    else
        fail "re-zip name-set differs:"; diff <(printf '%s\n' "$orig") <(printf '%s\n' "$rez") | sed 's/^/        /'
    fi
else
    echo "skip  committed archive absent"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
