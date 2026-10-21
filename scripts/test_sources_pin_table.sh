#!/usr/bin/env bash
# Fixture: the human-readable Pin Table is a generated projection of the
# machine-readable Source pins, not a second authored copy.
#
# WHY THIS EXISTS
#
# `sync-upstream` moved provenance/bepinex.env but left provenance/SOURCES.md
# and the describe comment in bepinex.env at the old pin. The two tables could
# disagree even though docs/ci-cd.md said they could not. This fixture pins the
# replacement contract:
#
#   provenance/*.env       the only authored pin values
#   provenance/pin-table.template.md
#                          row text with {{placeholder}} values
#   provenance/SOURCES.md  generated block, checked in and drift-tested
#   scripts/render_sources_pin_table.py
#                          --check fails when the projection is stale
#
# Every case runs locally; no network and no real patch replay.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GEN="$REPO/scripts/render_sources_pin_table.py"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pin-table.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fails=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; shift; [ "$#" -gt 0 ] && printf '%s\n' "$@" | sed 's/^/        /'; fails=$((fails + 1)); }

reset_fixture() {
    rm -rf "$WORK/provenance"
    cp -R "$REPO/provenance" "$WORK/provenance"
}

expect_fail() { # expect_fail <label> <needle> <command...>
    local label="$1" needle="$2" out rc
    shift 2
    out="$("$@" 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF -- "$needle"; then
        pass "$label"
    else
        fail "$label (exit $rc, wanted '$needle')" "$out"
    fi
}

echo "== render_sources_pin_table.py =="
echo

# 1. The committed table is current. This is the gate that would have caught
#    the stale SOURCES.md / bepinex.env pair.
if out="$("$GEN" --check 2>&1)"; then
    pass "committed Pin Table is current"
else
    fail "committed Pin Table is stale" "$out"
fi

reset_fixture
FAKE_PIN=1111111111111111111111111111111111111111
OTHER_PIN=2222222222222222222222222222222222222222
sed -i "s|^UPSTREAM_COMMIT=.*|UPSTREAM_COMMIT=$FAKE_PIN|" "$WORK/provenance/bepinex.env"

# 2. A changed Source pin must render into the table.
if out="$("$GEN" --root "$WORK" 2>&1)"; then
    if grep -q "\`$FAKE_PIN\`" "$WORK/provenance/SOURCES.md"; then
        pass "pin change renders into the generated block"
    else
        fail "pin change did not render into SOURCES.md" "$out"
    fi
else
    fail "renderer failed on the fixture root" "$out"
fi

# 3. The rendered fixture passes the same check the CI job runs.
if out="$("$GEN" --root "$WORK" --check 2>&1)"; then
    pass "rendered fixture passes --check"
else
    fail "rendered fixture did not pass --check" "$out"
fi

# 4. A hand edit inside the generated block must fail closed.
sed -i "s|$FAKE_PIN|$OTHER_PIN|" "$WORK/provenance/SOURCES.md"
expect_fail "hand-edited generated block is stale" "stale" \
    "$GEN" --root "$WORK" --check

# 5. Missing markers are a template/defect failure, not an empty table.
reset_fixture
sed -i '/BEGIN GENERATED PIN TABLE/d' "$WORK/provenance/SOURCES.md"
expect_fail "missing begin marker fails" "marker" \
    "$GEN" --root "$WORK" --check

# 6. A missing env value is refused rather than rendered blank.
reset_fixture
sed -i '/^UPSTREAM_COMMIT=/d' "$WORK/provenance/bepinex.env"
expect_fail "missing source pin fails" "missing UPSTREAM_COMMIT" \
    "$GEN" --root "$WORK" --check

# 7. An unknown placeholder is refused rather than published.
reset_fixture
printf '\n{{unknown.value}}\n' >> "$WORK/provenance/pin-table.template.md"
expect_fail "unknown template placeholder fails" "unknown placeholder" \
    "$GEN" --root "$WORK" --check

echo
if [ "$fails" -eq 0 ]; then
    echo "pass"
else
    echo "$fails failure(s)"
    exit 1
fi
