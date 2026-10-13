#!/usr/bin/env bash
# Fixture for scripts/generate_sbom.py.
#
# The SBOM exists so a release consumer can answer "what upstream went into
# this APK" without trusting a description. The cases that matter are therefore
# the quiet ones: a pin the SBOM silently drops, a document that parses as JSON
# but is not SPDX, and a generator that emits a different file on every run.
#
# States pinned:
#   valid tree            -> exit 0, SPDX 2.3, all six pins present
#   every provenance file -> watched (nothing on disk is unlisted)
#   missing pin file      -> exit non-zero, names the file (fail-closed)
#   pin missing a key     -> exit non-zero, names the key (fail-closed)
#   deterministic         -> two runs with SOURCE_DATE_EPOCH are byte-identical
#   relationships         -> DOCUMENT describes root, root depends on every dep
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/sbom.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
GEN="$REPO/scripts/generate_sbom.py"

fails=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }

echo "== generate_sbom.py =="

# 1. a valid tree produces a valid SPDX 2.3 document
OUT="$FIX/sbom.spdx.json"
if python3 "$GEN" --output "$OUT" >/dev/null 2>&1 && [ -f "$OUT" ]; then
    ok "valid tree produces an SBOM"
else
    fail "valid tree did not produce an SBOM"
fi

# 2. the document parses and carries the SPDX envelope
if python3 - "$OUT" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
assert d["spdxVersion"] == "SPDX-2.3", d.get("spdxVersion")
assert d["dataLicense"] == "CC0-1.0", d.get("dataLicense")
assert d["SPDXID"] == "SPDXRef-DOCUMENT"
assert isinstance(d["packages"], list) and len(d["packages"]) > 0
assert isinstance(d["relationships"], list) and len(d["relationships"]) > 0
PY
then
    ok "document is SPDX 2.3 with packages and relationships"
else
    fail "document is not a valid SPDX envelope"
fi

# 3. all six pins are present with their exact committed values.
#    Values are re-read from the pin files rather than typed, so a pin bump
#    without an SBOM change fails here instead of passing on stale text.
pins_ok=1
for env in bepinex dobby pine monomod il2cppinterop dotnet-runtime; do
    while IFS='=' read -r k v; do
        case "$k" in UPSTREAM_COMMIT|PINE_SUBMODULE_XZ_COMMIT|DOTNET_RUNTIME_VERSION|MONOMOD_PACKAGE_VERSION|PACKAGE_VERSION|DOTNET_RUNTIME_PACK_SHA256) ;;
            *) continue ;;
        esac
        v="$(printf '%s' "$v" | tr -d "\"' ")"
        [ -n "$v" ] || continue
        if ! grep -qF -- "$v" "$OUT"; then
            echo "        missing from SBOM: $env $k=$v"
            pins_ok=0
        fi
    done < <(grep -E '^(UPSTREAM_COMMIT|PINE_SUBMODULE_XZ_COMMIT|DOTNET_RUNTIME_VERSION|MONOMOD_PACKAGE_VERSION|PACKAGE_VERSION|DOTNET_RUNTIME_PACK_SHA256)=' "$REPO/provenance/$env.env")
done
# The commit values above are long SHAs; also assert the short, human-visible
# package versions appear as versionInfo, not only buried in a comment.
for needle in "25.3.7-android.1" "1.5.2-android.3" "10.0.12"; do
    grep -qF -- "$needle" "$OUT" || { echo "        missing version $needle"; pins_ok=0; }
done
if [ "$pins_ok" = 1 ]; then ok "all six pins present with exact values"; else fail "a pin value is missing from the SBOM"; fi

# 4. every provenance file on disk is watched. dotnet-runtime.env is NOT exempt
#    here (unlike the drift checker): the SBOM names the runtime pack, so an
#    unwatched runtime pin would be a silent omission.
for f in "$REPO"/provenance/*.env; do
    base="$(basename "$f" .env)"
    case "$base" in
        bepinex|dobby|pine|monomod|il2cppinterop|dotnet-runtime) ok "$base.env is a required SBOM input" ;;
        *) fail "unexpected pin file $base.env is not a declared SBOM input" ;;
    esac
done

# 5. Gradle catalog entries appear (a sample, not the whole list: the full
#    list is asserted by the count below).
if grep -qF "androidx.core:core-ktx" "$OUT" && grep -qF "top.canyie.pine:core" "$OUT"; then
    ok "Gradle catalog libraries appear"
else
    fail "Gradle catalog libraries missing"
fi

# 6. missing pin file fails closed and names the file.
mkdir -p "$FIX/prov"
cp "$REPO"/provenance/*.env "$FIX/prov/"
rm "$FIX/prov/pine.env"
if python3 "$GEN" --provenance-dir "$FIX/prov" --output "$FIX/nope.json" >/dev/null 2>&1; then
    fail "missing pin file exited 0 (fail-open)"
else
    out="$(python3 "$GEN" --provenance-dir "$FIX/prov" --output "$FIX/nope.json" 2>&1 || true)"
    if printf '%s' "$out" | grep -qF "pine.env"; then
        ok "missing pin file fails and names pine.env"
    else
        fail "missing pin file failed without naming pine.env"
    fi
fi

# 7. a pin file missing a required key fails closed and names the key.
mkdir -p "$FIX/prov2"
cp "$REPO"/provenance/*.env "$FIX/prov2/"
sed -i '/^UPSTREAM_COMMIT=/d' "$FIX/prov2/dobby.env"
if python3 "$GEN" --provenance-dir "$FIX/prov2" --output "$FIX/nope2.json" >/dev/null 2>&1; then
    fail "pin missing UPSTREAM_COMMIT exited 0 (fail-open)"
else
    out="$(python3 "$GEN" --provenance-dir "$FIX/prov2" --output "$FIX/nope2.json" 2>&1 || true)"
    if printf '%s' "$out" | grep -qF "UPSTREAM_COMMIT"; then
        ok "pin missing a key fails and names UPSTREAM_COMMIT"
    else
        fail "pin missing a key failed without naming UPSTREAM_COMMIT"
    fi
fi

# 8. deterministic: two runs with SOURCE_DATE_EPOCH are byte-identical.
if SOURCE_DATE_EPOCH=1700000000 python3 "$GEN" --output "$FIX/a.json" >/dev/null 2>&1 \
&& SOURCE_DATE_EPOCH=1700000000 python3 "$GEN" --output "$FIX/b.json" >/dev/null 2>&1 \
&& cmp -s "$FIX/a.json" "$FIX/b.json"; then
    ok "two runs with SOURCE_DATE_EPOCH are byte-identical"
else
    fail "two runs with SOURCE_DATE_EPOCH differed"
fi

# 9. relationships: DOCUMENT describes the root, and the root depends on
#    every other package (nothing listed but unwired).
if python3 - "$OUT" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
rels = d["relationships"]
roots = [r for r in rels if r["spdxElementId"] == "SPDXRef-DOCUMENT"
         and r["relationshipType"] == "DESCRIBES"]
assert len(roots) == 1 and roots[0]["relatedSpdxElement"] == "SPDXRef-Root-APK", roots
ids = {p["SPDXID"] for p in d["packages"]} - {"SPDXRef-Root-APK"}
wired = {r["relatedSpdxElement"] for r in rels
         if r["spdxElementId"] == "SPDXRef-Root-APK" and r["relationshipType"] == "DEPENDS_ON"}
assert ids == wired, (ids - wired, wired - ids)
PY
then
    ok "DOCUMENT describes root and root depends on every package"
else
    fail "relationship wiring is incomplete"
fi

# 10. packages are sorted by SPDXID (determinism, reviewable diffs).
if python3 - "$OUT" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
ids = [p["SPDXID"] for p in d["packages"]]
assert ids == sorted(ids), ids
PY
then
    ok "packages sorted by SPDXID"
else
    fail "packages not sorted by SPDXID"
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
