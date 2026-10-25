#!/usr/bin/env bash
# Fixture harness for scripts/verify_attestation.sh.
#
# This gate exists to stop a substituted artifact from reaching the shipping
# path, so the cases that matter are the quiet ones. `gh` is stubbed, which lets
# this assert the decision without touching the attestation API:
#
#   - a complete, correctly-attested tree PASSES.
#   - ONE unsigned file among signed ones FAILS, and is named. A gate that
#     passed because "most files were fine" is the shape this repository has
#     removed three times.
#   - an attestation from a different repository FAILS. Producer hashes cannot
#     express this; origin is the entire point of this gate.
#   - an attestation for a different commit FAILS. A run id is not its input.
#   - a missing gh FAILS rather than skipping. "Cannot check" and "checked" are
#     different states and only one of them may pass.
#
# The stub decides per file from a control file, so each case is a single edit
# rather than a new stub.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/attestation-gate.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/verify_attestation.sh"
COMMIT="abcdef1234567890abcdef1234567890abcdef12"

mkdir -p "$FIX/bin"

# Decision table: one "<basename> <verdict>" per line. verdict is ok | badrepo |
# badcommit. A file with no line is unsigned.
: > "$FIX/attested"

cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/gh-calls"
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] && [ "${3:-}" = "--help" ]; then
    exit 0
fi
[ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] || exit 1
target=""
prev=""
for a in "$@"; do
    [ "$prev" = "" ] || { [ "$prev" = "--source-ref" ] && ref="$a"; }
    # The --bundle value is a path but not the attestation subject; skip it.
    if [ "$prev" != "--bundle" ]; then
        case "$a" in /*) target="$(basename "$a")" ;; esac
    fi
    prev="$a"
done
want_repo="BepInEx-Android/BepInEx-Android"
got_repo=""
prev=""
for a in "$@"; do
    [ "$prev" = "--repo" ] && got_repo="$a"
    prev="$a"
done
# Fail the way gh does on a real mismatch, including on stdout, because a
# caller that treats gh's stdout as data is the class of bug this repo has
# already fixed once in this same workflow family.
if [ "$got_repo" != "$want_repo" ]; then
    echo '{"message":"no attestations found","status":"404"}' >&2
    exit 1
fi
# With --bundle the caller verifies a downloaded Sigstore bundle instead of
# querying the API; there is no --source-ref to check (the bundle carries its
# own statement). Without --bundle the ref must match.
has_bundle=0
for a in "$@"; do [ "$a" = "--bundle" ] && has_bundle=1; done
if [ "$has_bundle" -eq 0 ] && [ "${ref:-}" != "$WORK_HEAD_SHA" ]; then
    echo "commit mismatch" >&2
    exit 1
fi
verdict="$(awk -v f="$target" '$1 == f { print $2 }' "$WORK/attested")"
case "$verdict" in
    ok) exit 0 ;;
    badrepo)
        echo "attestation is from another repository" >&2
        exit 1 ;;
    badcommit)
        echo "attestation is for another commit" >&2
        exit 1 ;;
    *)
        echo '{"message":"no attestations found","status":"404"}' >&2
        exit 1 ;;
esac
STUB
chmod +x "$FIX/bin/gh"

export WORK="$FIX"
export WORK_HEAD_SHA="$COMMIT"

stage() {
    rm -rf "$FIX/launcher"
    JNI="$FIX/launcher/app/src/main/jniLibs/arm64-v8a"
    AS="$FIX/launcher/app/src/main/assets"
    mkdir -p "$JNI" "$AS"
    for f in libcoreclr.so libclrjit.so libSystem.Native.so \
             libSystem.Globalization.Native.so libSystem.IO.Compression.Native.so \
             libSystem.Security.Cryptography.Native.Android.so \
             libmscordaccore.so libmscordbi.so libdobby.so liblsplant.so; do
        printf 'native:%s\n' "$f" > "$JNI/$f"
        echo "$f ok" >> "$FIX/attested"
    done
    printf 'bcl\n'   > "$AS/dotnet.zip";           echo "dotnet.zip ok" >> "$FIX/attested"
    printf 'managed\n' > "$AS/BepInEx.Android.zip"; echo "BepInEx.Android.zip ok" >> "$FIX/attested"
}

run_gate() {
    : > "$FIX/gh-calls"
    PATH="$FIX/bin:$PATH" \
    GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
    GITHUB_SHA="$COMMIT" \
    "$SCRIPT" "$FIX" 2>&1
    printf '%s' "$?" > "$FIX/rc"
}

fails=0
check_rc() { # check_rc <want> <label>
    local want="$1" label="$2" rc
    rc="$(cat "$FIX/rc")"
    if [ "$rc" = "$want" ]; then
        echo "ok    $label"
    else
        echo "FAIL  $label: expected exit $want, got $rc"
        fails=$((fails + 1))
    fi
}
check_out() { # check_out <label> <needle>  (absent if needle is the word "absent")
    local label="$1" needle="$2" out
    out="$(cat "$FIX/out")"
    if [ "$needle" = absent ]; then
        if printf '%s' "$out" | grep -qF -- "$needle"; then
            echo "FAIL  $label"; fails=$((fails + 1))
        else
            echo "ok    $label"
        fi
        return
    fi
    if printf '%s' "$out" | grep -qF -- "$needle"; then
        echo "ok    $label"
    else
        echo "FAIL  $label: output did not mention '$needle'"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
}

echo "== verify_attestation.sh =="

# 1. a complete, correctly attested tree passes
: > "$FIX/attested"
stage
run_gate > "$FIX/out"
check_rc 0 "a fully attested tree passes"
check_out "and reports every file" "every installed artifact carries a provenance attestation"

# 2. ONE unsigned file among signed ones fails, and is named
: > "$FIX/attested"
stage
sed -i '/^liblsplant\.so ok$/d' "$FIX/attested"
run_gate > "$FIX/out"
check_rc 1 "one unsigned file fails the gate"
check_out "and names the file" "liblsplant.so"
check_out "and says what is missing" "no valid provenance attestation"

# 3. an attestation from another repository fails
: > "$FIX/attested"
stage
sed -i 's/^libdobby\.so ok$/libdobby.so badrepo/' "$FIX/attested"
run_gate > "$FIX/out"
check_rc 1 "an attestation from another repository fails"
check_out "and names the file" "libdobby.so"

# 4. an attestation for another commit fails
: > "$FIX/attested"
stage
sed -i 's/^BepInEx\.Android\.zip ok$/BepInEx.Android.zip badcommit/' "$FIX/attested"
run_gate > "$FIX/out"
check_rc 1 "an attestation for another commit fails"
check_out "and names the file" "BepInEx.Android.zip"

# 5. every file unsigned fails - the whole gate does not skip
: > "$FIX/attested"
stage
: > "$FIX/attested"
run_gate > "$FIX/out"
check_rc 1 "an entirely unattested tree fails"

# 6. a missing gh must fail, not skip. The PATH keeps the ordinary shell tools
# reachable but drops gh: an empty PATH would fail to find bash itself (127) and
# would be testing the wrong thing.
: > "$FIX/attested"
stage
: > "$FIX/gh-calls"
mkdir -p "$FIX/nogh"
for t in bash sh basename cat grep awk rm mkdir printf sed env; do
    p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$FIX/nogh/$t"
done
rc="$(PATH="$FIX/nogh" GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" GITHUB_SHA="$COMMIT" \
      "$SCRIPT" "$FIX" >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    a missing gh is an error, not a skip"
else
    echo "FAIL  a missing gh exited 0"; fails=$((fails + 1))
fi

# 7. missing context must fail rather than pass on unset GITHUB_SHA
: > "$FIX/attested"
stage
rc="$(env -u GITHUB_REPOSITORY -u GITHUB_SHA "$SCRIPT" "$FIX" >/dev/null 2>&1; echo $?)"
if [ "$rc" -ne 0 ]; then
    echo "ok    missing repository/commit context is an error"
else
    echo "FAIL  missing context exited 0"; fails=$((fails + 1))
fi

# 8. a gh that exists but has no `attestation` subcommand must be named as
# that, not reported as every file being unattested. This is measured, not
# hypothetical: gh 2.46 - the version on the machine this was written on - has
# no attestation command, so a gate that only checks for gh on PATH turns a
# too-old runner into twelve false "unattested" verdicts.
: > "$FIX/attested"
stage
cat > "$FIX/bin/gh" <<'OLDGH'
#!/usr/bin/env bash
if [ "$1" = "attestation" ]; then
    echo 'unknown command "attestation" for "gh"' >&2
    exit 1
fi
exit 0
OLDGH
chmod +x "$FIX/bin/gh"
out="$(PATH="$FIX/bin:$PATH" GITHUB_REPOSITORY="BepInEx-Android/BepInEx-Android" \
       GITHUB_SHA="$COMMIT" "$SCRIPT" "$FIX" 2>&1)"; rc=$?
if [ "$rc" -ne 0 ]; then
    echo "ok    a gh without the attestation subcommand is an error"
else
    echo "FAIL  an old gh exited 0"; fails=$((fails + 1))
fi
if printf '%s' "$out" | grep -qF "no 'attestation' command"; then
    echo "ok    and it says the subcommand is missing, not that files are unattested"
else
    echo "FAIL  the cause was not named:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if printf '%s' "$out" | grep -qF "[FAIL]"; then
    echo "FAIL  it reported per-file failures instead of the real cause"
    fails=$((fails + 1))
else
    echo "ok    and it did not mislabel every artifact as unattested"
fi

# Restore the standard stub gh for fallback provenance bundle tests
cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] && [ "${3:-}" = "--help" ]; then
    exit 0
fi
[ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] || exit 1
# Always return 404 to simulate private repo behavior
echo '{"message":"no attestations found","status":"404"}' >&2
exit 1
STUB
chmod +x "$FIX/bin/gh"

# 9. a valid fallback provenance bundle passes when Sigstore is unavailable
: > "$FIX/attested"
stage
: > "$FIX/attested"
rm -f "$FIX"/*.attestation.json
python3 "$REPO/scripts/emit_fallback_attestation.py" \
    --paths "$FIX/launcher/app/src/main/jniLibs/arm64-v8a/*.so
$FIX/launcher/app/src/main/assets/*.zip" \
    --output "$FIX/test.attestation.json" \
    --repository "BepInEx-Android/BepInEx-Android" \
    --commit "$COMMIT" \
    --run-id "99999" >/dev/null
run_gate > "$FIX/out"
check_rc 0 "fallback provenance bundle passes when Sigstore is unavailable"
check_out "and reports fallback provenance" "fallback provenance"

# 10. fallback provenance bundle from another repository fails
rm -f "$FIX"/*.attestation.json
python3 "$REPO/scripts/emit_fallback_attestation.py" \
    --paths "$FIX/launcher/app/src/main/jniLibs/arm64-v8a/*.so
$FIX/launcher/app/src/main/assets/*.zip" \
    --output "$FIX/test.attestation.json" \
    --repository "OtherOwner/OtherRepo" \
    --commit "$COMMIT" \
    --run-id "99999" >/dev/null
run_gate > "$FIX/out"
check_rc 1 "fallback bundle from another repository fails"

# 11. fallback provenance bundle for another commit fails
rm -f "$FIX"/*.attestation.json
python3 "$REPO/scripts/emit_fallback_attestation.py" \
    --paths "$FIX/launcher/app/src/main/jniLibs/arm64-v8a/*.so
$FIX/launcher/app/src/main/assets/*.zip" \
    --output "$FIX/test.attestation.json" \
    --repository "BepInEx-Android/BepInEx-Android" \
    --commit "1111111111111111111111111111111111111111" \
    --run-id "99999" >/dev/null
run_gate > "$FIX/out"
check_rc 1 "fallback bundle for another commit fails"

# 12. tampered file in fallback bundle fails
rm -f "$FIX"/*.attestation.json
python3 "$REPO/scripts/emit_fallback_attestation.py" \
    --paths "$FIX/launcher/app/src/main/jniLibs/arm64-v8a/*.so
$FIX/launcher/app/src/main/assets/*.zip" \
    --output "$FIX/test.attestation.json" \
    --repository "BepInEx-Android/BepInEx-Android" \
    --commit "$COMMIT" \
    --run-id "99999" >/dev/null
printf 'tampered_content\n' >> "$FIX/launcher/app/src/main/jniLibs/arm64-v8a/liblsplant.so"
run_gate > "$FIX/out"
check_rc 1 "tampered file with fallback bundle fails"
check_out "and names the tampered file" "liblsplant.so"

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"