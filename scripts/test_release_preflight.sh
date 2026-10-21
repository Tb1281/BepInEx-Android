#!/usr/bin/env bash
# Fixture for scripts/release_preflight.sh.
#
# WHY THIS EXISTS
#
# The preflight is the only thing standing between a pushed tag and a published
# release. Its value is entirely in the failure directions: a tag off main, a
# commit the `verified` gate never saw, a reused version, or a substituted key
# must each stop the build. `git`, `gh`, and `keytool` are stubbed (same precedent
# as test_verify_release_assets.sh), so every case feeds one bad input and
# asserts the gate closes.
#
# Cases:
#   stable tag on main, covered, monotonic, key matches -> PASS
#   pre-release tag with a newer base                  -> PASS (marked pre-release)
#   malformed / non-v / `verified` tag                 -> FAIL
#   tag not on remote main                             -> FAIL
#   tag not covered by remote verified                 -> FAIL
#   stale local verified ref                            -> PASS, and the local ref is never read
#   stable version <= highest stable tag               -> FAIL
#   stable release already exists for the tag          -> FAIL
#   pre-release below the highest stable tag           -> FAIL
#   pre-release above the highest stable tag           -> PASS
#   rc2 above rc1, and stable above both                -> PASS
#   pre-release rank 0, rank 99+, or major above 20     -> FAIL
#   missing release secret                             -> FAIL, names the value
#   unreadable keystore / wrong alias or password       -> FAIL
#   wrong key certificate                              -> FAIL
#   public certificate disagrees with fingerprint      -> FAIL
#   --verify-only without a key or gh                  -> PASS, no keystore
#   build without a key                                -> FAIL
#   remote tag list unavailable                        -> FAIL
#   release list unavailable                           -> FAIL
#   missing tag                                        -> FAIL
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/release-preflight-fix.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
export FIX
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/release_preflight.sh"

TAG_SHA="1111111111111111111111111111111111111111"
MAIN_SHA="2222222222222222222222222222222222222222"
VERIFIED_SHA="3333333333333333333333333333333333333333"
# Deliberately present, deliberately unrelated to the tag or remote verified
# ref. A script that treats the local `verified` ref as authoritative reads this
# value and fails coverage instead of passing on the remote result.
LOCAL_VERIFIED_SHA="4444444444444444444444444444444444444444"
export LOCAL_VERIFIED_SHA
GOOD_FP="$(tr -d '[:space:]' < "$REPO/provenance/release-cert.sha256" | tr 'A-F' 'a-f')"
GOOD_FP_COLON="$(printf '%s' "$GOOD_FP" | fold -w2 | paste -sd: | tr 'a-f' 'A-F')"
BAD_FP_COLON="00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"
printf '%s\n' "v1.2.2" "v1.1.0" > "$FIX/releases"
printf '%s\n' "$(printf '0%.0s' {1..64})" > "$FIX/bad-fingerprint"

mkdir -p "$FIX/bin"
cat > "$FIX/bin/git" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "\$FIX/git-calls"
mode="\$(cat "\$FIX/mode" 2>/dev/null || echo ok)"
tag="\${TAG_UNDER_TEST:-}"
case "\$*" in
    "rev-parse --verify --quiet \${tag}^{commit}")
        [ "\$mode" = "tagnotfound" ] && exit 1
        printf '%s\n' "$TAG_SHA"; exit 0 ;;
    "rev-parse --verify --quiet verified^{commit}")
        printf '%s\n' "$LOCAL_VERIFIED_SHA"; exit 0 ;;
    "ls-remote --heads origin main")
        printf '%s\trefs/heads/main\n' "$MAIN_SHA"; exit 0 ;;
    "ls-remote --tags origin refs/tags/verified")
        printf '%s\trefs/tags/verified\n' "$VERIFIED_SHA"; exit 0 ;;
    "ls-remote --tags origin refs/tags/v"*)
        [ "\$mode" = "taglistfail" ] && exit 1
        while IFS= read -r released_tag; do
            [ -n "\$released_tag" ] || continue
            printf '%040d\trefs/tags/%s\n' 0 "\$released_tag"
        done < "\$FIX/releases"
        exit 0 ;;
    "cat-file -e "*)
        exit 0 ;;
    "merge-base --is-ancestor "*)
        set -- \$*
        if [ "\$mode" = "notmain" ] && [ "\${4:-}" = "$MAIN_SHA" ]; then exit 1; fi
        if [ "\$mode" = "notverified" ] && [ "\${4:-}" = "$VERIFIED_SHA" ]; then exit 1; fi
        # Reading the stale local ref would use this unrelated commit, which
        # cannot cover a tag on main.
        if [ "\${4:-}" = "$LOCAL_VERIFIED_SHA" ]; then exit 1; fi
        exit 0 ;;
    "fetch --quiet --no-tags origin refs/tags/verified")
        exit 0 ;;
    *)
        exit 0 ;;
esac
STUB
chmod +x "$FIX/bin/git"

cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FIX/gh-calls"
mode="$(cat "$FIX/mode" 2>/dev/null || echo ok)"
case "$*" in
    "release list "*)
        [ "$mode" = "ghfail" ] && exit 1
        [ "$mode" = "equalstable" ] && printf '%s\n' "$TAG_UNDER_TEST"
        exit 0 ;;
    *)
        exit 1 ;;
esac
STUB
chmod +x "$FIX/bin/gh"

cat > "$FIX/bin/keytool" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "\$FIX/keytool-calls"
mode="\$(cat "\$FIX/mode" 2>/dev/null || echo ok)"
if [ "\$mode" = "badkeystore" ]; then
    echo "keytool error: failed to decrypt safe contents entry" >&2
    exit 1
fi
if [ "\$mode" = "badalias" ]; then
    echo "keytool error: java.lang.Exception: Alias <missing> does not exist" >&2
    exit 1
fi
if [ "\$mode" = "badstorepass" ]; then
    echo "keytool error: java.io.IOException: keystore password was incorrect" >&2
    exit 1
fi
if [ "\$mode" = "badfingerprint" ]; then
    printf 'SHA256: %s\n' "$BAD_FP_COLON"
else
    printf 'SHA256: %s\n' "$GOOD_FP_COLON"
fi
STUB
chmod +x "$FIX/bin/keytool"

# A PATH with every helper except gh, so the missing-gh direction is real.
NOGH="$FIX/bin-nogh"
mkdir -p "$NOGH"
cp "$FIX/bin/git" "$NOGH/git"
cp "$FIX/bin/keytool" "$NOGH/keytool"
for cmd in bash base64 openssl awk sed tr head paste fold mktemp mkdir chmod rm dirname cat; do
    p="$(command -v "$cmd" 2>/dev/null)" && ln -sf "$p" "$NOGH/$cmd"
done

fails=0
case_no=0
LAST_OUT=""

value_of() { # value_of <key> <file>
    sed -n "s/^$1=//p" "$2" 2>/dev/null | tail -1
}

run_case() { # run_case <label> <want-rc> <mode> <tag> <--build|--verify-only> [env...]
    local label="$1" want="$2" mode="$3" tag="$4"; shift 4
    local flag="--build"
    if [ "$#" -gt 0 ]; then
        flag="$1"
        shift
    fi
    local verify=0
    [ "$flag" = "--verify-only" ] && verify=1

    case_no=$((case_no + 1))
    local slug="case-$case_no"
    local out="$FIX/$slug.out"
    local ks="$FIX/keystore-$slug"
    local ghout="$FIX/github-output-$slug"
    LAST_OUT="$out"
    rm -f "$ks" "$ghout"
    echo "$mode" > "$FIX/mode"
    : > "$FIX/git-calls"
    : > "$FIX/gh-calls"
    : > "$FIX/keytool-calls"

    local path="$FIX/bin:$PATH"
    [ "$mode" = "nogh" ] && path="$NOGH"

    local args=("$SCRIPT")
    [ "$verify" -eq 1 ] && args+=("--verify-only")
    args+=("$tag")

    local envs=(
        "PATH=$path"
        "FIX=$FIX"
        "GITHUB_REPOSITORY=o/r"
        "GITHUB_OUTPUT=$ghout"
        "PREFLIGHT_KEYSTORE_OUT=$ks"
        "PREFLIGHT_CERT_FILE=$REPO/provenance/release-cert.pem"
        "PREFLIGHT_FINGERPRINT_FILE=$REPO/provenance/release-cert.sha256"
        "TAG_UNDER_TEST=$tag"
    )
    if [ "$mode" != "nokey" ]; then
        envs+=(
            "RELEASE_KEYSTORE_BASE64=$(printf 'fake-keystore' | base64 | tr -d '\n')"
            "RELEASE_KEYSTORE_PASSWORD=storepass"
            "RELEASE_KEY_ALIAS=bepinex-release"
            "RELEASE_KEY_PASSWORD=keypass"
        )
    fi
    [ "$mode" = "badcert" ] && envs+=("PREFLIGHT_FINGERPRINT_FILE=$FIX/bad-fingerprint")

    local output rc
    output="$(env -i "${envs[@]}" "$@" "${args[@]}" 2>&1)"; rc=$?
    printf '%s\n' "$output" > "$out"

    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$output" | sed 's/^/        /'
        fails=$((fails + 1))
        return
    fi

    if [ "$want" -eq 0 ] && [ "$verify" -eq 0 ]; then
        if [ ! -f "$ks" ]; then
            echo "FAIL  $label: successful build preflight did not leave a keystore"
            fails=$((fails + 1))
            return
        fi
    elif [ -f "$ks" ]; then
        echo "FAIL  $label: failure/verify-only preflight left a keystore behind"
        fails=$((fails + 1))
        return
    fi

    echo "ok    $label (exit $rc)"
}

echo "== release_preflight.sh =="

run_case "stable tag on main passes" 0 ok v1.2.3 --build
[ "$(value_of version_name "$LAST_OUT")" = "1.2.3" ] \
    && echo "ok    stable version_name" || { echo "FAIL  stable version_name"; fails=$((fails + 1)); }
stable_code="$(value_of version_code "$LAST_OUT")"
[ "$stable_code" = "100200399" ] \
    && echo "ok    stable version_code" || { echo "FAIL  stable version_code: $stable_code"; fails=$((fails + 1)); }
[ "$(value_of is_prerelease "$LAST_OUT")" = "0" ] \
    && echo "ok    stable is_prerelease=0" || { echo "FAIL  stable is_prerelease"; fails=$((fails + 1)); }
[ -n "$(value_of keystore_path "$LAST_OUT")" ] \
    && echo "ok    stable keystore_path emitted" || { echo "FAIL  stable keystore_path"; fails=$((fails + 1)); }

run_case "pre-release tag passes" 0 ok v1.3.0-rc1 --build
[ "$(value_of version_name "$LAST_OUT")" = "1.3.0-rc1" ] \
    && echo "ok    pre-release version_name" || { echo "FAIL  pre-release version_name"; fails=$((fails + 1)); }
[ "$(value_of version_code "$LAST_OUT")" = "100300001" ] \
    && echo "ok    pre-release version_code" || { echo "FAIL  pre-release version_code"; fails=$((fails + 1)); }
[ "$(value_of is_prerelease "$LAST_OUT")" = "1" ] \
    && echo "ok    pre-release is_prerelease=1" || { echo "FAIL  pre-release is_prerelease"; fails=$((fails + 1)); }

run_case "verified tag is not a release tag" 1 ok verified --build
run_case "malformed tag fails" 1 ok v1.2 --build
run_case "non-v tag fails" 1 ok 1.2.3 --build
run_case "leading zero fails" 1 ok v01.2.3 --build
run_case "major version above 20 fails" 1 ok v21.0.0 --build
run_case "tag not on main fails" 1 notmain v1.2.3 --build
run_case "tag not covered by verified fails" 1 notverified v1.2.3 --build
run_case "tag not found fails" 1 tagnotfound v1.2.3 --build

# v1.2.3-rc1 (100200301) must be newer than v1.2.2 stable (100200299) and
# older than v1.2.3 stable (100200399). v1.2.3-rc2 (100200302) sits between
# them, so the pre-release ordinal, not only the stable/pre-release channel,
# is ordered.
echo "v1.2.2" > "$FIX/releases"
run_case "pre-release above last stable passes" 0 ok v1.2.3-rc1 --build
rc1_code="$(value_of version_code "$LAST_OUT")"
[ "$rc1_code" = "100200301" ] \
    && echo "ok    rc1 version_code" || { echo "FAIL  rc1 version_code: $rc1_code"; fails=$((fails + 1)); }

run_case "pre-release rc2 passes" 0 ok v1.2.3-rc2 --build
rc2_code="$(value_of version_code "$LAST_OUT")"
[ "$rc2_code" = "100200302" ] \
    && echo "ok    rc2 version_code" || { echo "FAIL  rc2 version_code: $rc2_code"; fails=$((fails + 1)); }
[ "$rc2_code" -gt "$rc1_code" ] \
    && echo "ok    rc2 is newer than rc1" || { echo "FAIL  rc2 is not newer than rc1"; fails=$((fails + 1)); }
[ "$stable_code" -gt "$rc2_code" ] \
    && echo "ok    stable is newer than rc2" || { echo "FAIL  stable is not newer than rc2"; fails=$((fails + 1)); }
[ "$rc1_code" -lt "$stable_code" ] \
    && echo "ok    rc1 is older than stable" || { echo "FAIL  rc1 is not older than stable"; fails=$((fails + 1)); }

# The ordinal is the trailing decimal run, not a fixed rcN shape: a dot before
# the number, a named label, or no number at all still derives a rank.
run_case "dotted pre-release ordinal parses" 0 ok v1.2.3-rc.2 --build
[ "$(value_of version_code "$LAST_OUT")" = "100200302" ] \
    && echo "ok    rc.2 version_code" || { echo "FAIL  rc.2 version_code"; fails=$((fails + 1)); }
run_case "named pre-release ordinal parses" 0 ok v1.2.3-beta10 --build
[ "$(value_of version_code "$LAST_OUT")" = "100200310" ] \
    && echo "ok    beta10 version_code" || { echo "FAIL  beta10 version_code"; fails=$((fails + 1)); }
run_case "pre-release without a number is first" 0 ok v1.2.3-rc --build
[ "$(value_of version_code "$LAST_OUT")" = "100200301" ] \
    && echo "ok    unnumbered pre-release version_code" || { echo "FAIL  unnumbered pre-release version_code"; fails=$((fails + 1)); }

run_case "pre-release rank 0 fails" 1 ok v1.2.3-rc0 --build
run_case "pre-release rank 99 fails" 1 ok v1.2.3-rc99 --build

echo "v1.2.3" > "$FIX/releases"
run_case "non-monotonic stable fails" 1 nonmonotonic v1.2.2 --build
run_case "pre-release below last stable fails" 1 nonmonotonic v1.2.3-rc1 --build
echo "v1.2.2" > "$FIX/releases"
run_case "stable release already exists fails" 1 equalstable v1.2.3 --build
printf '%s\n' "v1.2.2" "v1.1.0" > "$FIX/releases"

# The remote is authoritative. The local ref is deliberately present and points
# at an unrelated commit, so a script that read it would fail coverage; the log
# assertion catches the read explicitly even if the verdict happened to pass.
run_case "stale local verified is never read" 0 ok v1.2.3 --build
if grep -q "rev-parse.*verified" "$FIX/git-calls"; then
    echo "FAIL  stale local verified: preflight read a local verified ref"
    fails=$((fails + 1))
else
    echo "ok    stale local verified: local ref was not read"
fi

for secret in RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    run_case "missing $secret fails" 1 ok v1.2.3 --build "$secret="
    if grep -q "$secret" "$LAST_OUT"; then
        echo "ok    missing secret names $secret"
    else
        echo "FAIL  missing secret did not name $secret"
        fails=$((fails + 1))
    fi
done

run_case "unreadable keystore fails" 1 badkeystore v1.2.3 --build
run_case "wrong key alias fails" 1 badalias v1.2.3 --build
run_case "wrong keystore password fails" 1 badstorepass v1.2.3 --build
run_case "wrong key certificate fails" 1 badfingerprint v1.2.3 --build
run_case "public certificate mismatch fails" 1 badcert v1.2.3 --build
run_case "verify-only without a key or gh passes" 0 nogh v1.2.3 --verify-only
run_case "build without a key fails" 1 nokey v1.2.3 --build
run_case "missing gh fails" 1 nogh v1.2.3 --build
run_case "missing repository fails" 1 ok v1.2.3 --build "GITHUB_REPOSITORY="
run_case "remote tag list unavailable fails" 1 taglistfail v1.2.3 --build
run_case "release list unavailable fails" 1 ghfail v1.2.3 --build
run_case "missing tag fails" 1 ok "" --build

echo ""
if [ "$fails" -eq 0 ]; then
    echo "ALL FIXTURE CASES PASSED"
else
    echo "$fails CASE(S) FAILED"
fi
exit "$fails"
