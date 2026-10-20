#!/usr/bin/env bash
# Fixture for scripts/verify_release_assets.sh.
#
# WHY THIS EXISTS
#
# The script is the only consumer-side check on the artifact users install,
# so its failure directions are the whole value: a release page showing a
# stale APK with a fresh-looking bundle must FAIL here, not pass. `gh` and
# `apksigner` are stubbed (same precedent as test_attestation_gate.sh), so each
# case feeds the stubs canned release assets and a verdict:
#
#   - complete release (all assets, checksum, bundle and signer agreeing) PASSES.
#   - a pre-release PASSES without --require-stable.
#   - a stable release PASSES with --require-stable; a pre-release FAILS it.
#   - a draft release FAILS rather than counting as published.
#   - tampered APK (checksum mismatch) FAILS.
#   - a bundle that does not vouch for the APK FAILS.
#   - any missing required asset FAILS rather than falling back to "unverified".
#   - an APK signed by a different certificate FAILS.
#   - multiple signers FAIL.
#   - missing gh, apksigner, openssl, or gh without `attestation` FAILS.
#   - unknown tag FAILS.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/release-verify-fix.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
export FIX
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export REPO
SCRIPT="$REPO/scripts/verify_release_assets.sh"
BASH_BIN="$(command -v bash)"

GOOD_FP="$(tr -d '[:space:]' < "$REPO/provenance/release-cert.sha256" | tr 'A-F' 'a-f')"
GOOD_FP_COLON="$(printf '%s' "$GOOD_FP" | fold -w2 | paste -sd: | tr 'a-f' 'A-F')"
printf '%s\n' "$GOOD_FP_COLON" > "$FIX/good-fp"

mkdir -p "$FIX/bin"
cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "--version" ]; then echo "gh version 2.80.0"; exit 0; fi
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] && [ "${3:-}" = "--help" ]; then
    if [ "${NO_ATTESTATION_CMD:-0}" = 1 ]; then exit 1; fi
    exit 0
fi
mode="$(cat "$FIX/mode" 2>/dev/null || echo ok)"
if [ "${1:-}" = "release" ] && [ "${2:-}" = "download" ]; then
    if [ "$mode" = "notrelease" ]; then
        echo "release not found" >&2
        exit 1
    fi
    dest=""
    prev=""
    for a in "$@"; do
        [ "$prev" = "--dir" ] && dest="$a"
        prev="$a"
    done
    [ -n "$dest" ] || exit 1
    if [ "$mode" != "noapk" ]; then
        printf 'fake-apk-bytes-%s' "$mode" > "$dest/app-release-aligned.apk"
    fi
    if [ "$mode" != "nochecksum" ] && [ "$mode" != "noapk" ]; then
        if [ "$mode" = "tampered" ]; then
            printf 'other-bytes' | sha256sum | awk '{print $1"  app-release-aligned.apk"}' > "$dest/app-release-aligned.apk.sha256"
        else
            sha256sum "$dest/app-release-aligned.apk" | sed 's| .*|  app-release-aligned.apk|' > "$dest/app-release-aligned.apk.sha256"
        fi
    fi
    [ "$mode" = "nosbom" ] || printf '{"spdxVersion":"SPDX-2.3"}\n' > "$dest/sbom.spdx.json"
    [ "$mode" = "noprovenance" ] || printf '{"commit":"stub"}\n' > "$dest/provenance.json"
    [ "$mode" = "nobundle" ] || printf '{"bundle":"stub"}\n' > "$dest/apk.attestation.json"
    [ "$mode" = "nolicense" ] || cp "$REPO/LICENSE" "$dest/LICENSE"
    [ "$mode" = "nonotices" ] || cp "$REPO/launcher/THIRDPARTY_NATIVE.md" "$dest/THIRDPARTY_NATIVE.md"
    if [ "$mode" = "nocert" ]; then
        :
    elif [ "$mode" = "badcert" ]; then
        printf 'not a certificate\n' > "$dest/release-cert.pem"
    else
        cp "$REPO/provenance/release-cert.pem" "$dest/release-cert.pem"
    fi
    if [ "$mode" = "nofingerprint" ]; then
        :
    elif [ "$mode" = "badfingerprint" ]; then
        printf '%064d\n' 0 > "$dest/release-cert.sha256"
    else
        cp "$REPO/provenance/release-cert.sha256" "$dest/release-cert.sha256"
    fi
    [ "$mode" = "extraapk" ] && printf 'extra' > "$dest/other.apk"
    exit 0
fi
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ]; then
    [ "$mode" = "badbundle" ] && exit 1
    exit 0
fi
if [ "${1:-}" = "release" ] && [ "${2:-}" = "view" ]; then
    case "$mode" in
        stable) printf 'false\tfalse\n' ;;
        draft)  printf 'true\ttrue\n' ;;
        *)      printf 'false\ttrue\n' ;;
    esac
    exit 0
fi
exit 1
STUB
chmod +x "$FIX/bin/gh"

cat > "$FIX/bin/apksigner" <<'STUB'
#!/usr/bin/env bash
mode="$(cat "$FIX/mode" 2>/dev/null || echo ok)"
if [ "${1:-}" != "verify" ] || [ "${2:-}" != "--print-certs" ]; then
    exit 1
fi
[ "$mode" = "badsignature" ] && exit 1
if [ "$mode" = "wrongsigner" ]; then
    fp="$(printf '%064d' 0)"
else
    fp="$(cat "$FIX/good-fp")"
fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$fp"
if [ "$mode" = "multisigner" ]; then
    printf 'Signer #2 certificate SHA-256 digest: %s\n' "$(printf '%064d' 0)"
fi
exit 0
STUB
chmod +x "$FIX/bin/apksigner"

fails=0

run_case() { # run_case <label> <want-rc> <mode> [--require-stable]
    local label="$1" want="$2" mode="$3"; shift 3
    local args=()
    [ "${1:-}" = "--require-stable" ] && args+=(--require-stable)
    echo "$mode" > "$FIX/mode"
    local out
    out="$(mktemp -d "$FIX/out.XXXXXX")"
    local output rc
    output="$(env PATH="$FIX/bin:$PATH" GITHUB_REPOSITORY=o/r "$SCRIPT" "${args[@]}" v9.9 "$out" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$output" | sed 's/^/        /'
        fails=$((fails + 1))
    else
        echo "ok    $label (exit $rc)"
    fi
    rm -rf "$out"
}

check_failure_message() { # check_failure_message <label> <expected message> <bin-path> [env...]
    local label="$1" expected="$2" path="$3"; shift 3
    local out
    out="$(mktemp -d "$FIX/out.XXXXXX")"
    local output rc
    output="$(env PATH="$path" GITHUB_REPOSITORY=o/r "$@" "$BASH_BIN" "$SCRIPT" v9.9 "$out" 2>&1)"; rc=$?
    if [ "$rc" -eq 0 ]; then
        echo "FAIL  $label: expected failure, got success"
        fails=$((fails + 1))
    elif ! printf '%s\n' "$output" | grep -q "$expected"; then
        echo "FAIL  $label: failure did not say '$expected'"
        printf '%s\n' "$output" | sed 's/^/        /'
        fails=$((fails + 1))
    else
        echo "ok    $label (exit $rc)"
    fi
    rm -rf "$out"
}

echo "== verify_release_assets.sh =="
run_case "complete pre-release passes" 0 ok
run_case "complete stable release passes" 0 stable
run_case "stable release passes with --require-stable" 0 stable --require-stable
run_case "pre-release fails --require-stable" 1 prerelease --require-stable
run_case "draft release fails" 1 draft
run_case "tampered APK fails" 1 tampered
run_case "unvouched bundle fails" 1 badbundle
run_case "missing APK fails" 1 noapk
run_case "missing checksum fails" 1 nochecksum
run_case "missing SBOM fails" 1 nosbom
run_case "missing provenance fails" 1 noprovenance
run_case "missing bundle fails" 1 nobundle
run_case "missing license fails" 1 nolicense
run_case "missing notices fails" 1 nonotices
run_case "missing certificate fails" 1 nocert
run_case "missing fingerprint fails" 1 nofingerprint
run_case "extra APK fails" 1 extraapk
run_case "wrong signer fails" 1 wrongsigner
run_case "invalid signature fails" 1 badsignature
run_case "multiple signers fail" 1 multisigner
run_case "invalid certificate fails" 1 badcert
run_case "wrong fingerprint file fails" 1 badfingerprint
run_case "unknown tag fails" 1 notrelease

# Missing gh fails rather than skipping. Keep the few coreutils the script
# needs to read the trusted fingerprint, but leave gh out; the script's own
# probe must be what reports the failure, not the shebang lookup.
mkdir -p "$FIX/bin-nogh"
ln -sf "$BASH_BIN" "$FIX/bin-nogh/bash"
for cmd in dirname tr; do
    ln -sf "$(command -v "$cmd")" "$FIX/bin-nogh/$cmd"
done
check_failure_message "missing gh fails" "gh is not on PATH" "$FIX/bin-nogh"
# gh without the attestation subcommand fails rather than misreporting.
mkdir -p "$FIX/bin-nocmd"
printf '#!/bin/sh\nif [ "$1" = "--version" ]; then echo "gh version 2.0.0"; fi\nexit 1\n' > "$FIX/bin-nocmd/gh"
chmod +x "$FIX/bin-nocmd/gh"
check_failure_message "gh without attestation fails" "no 'attestation' command" "$FIX/bin-nocmd:/usr/bin:/bin"
# Missing apksigner fails rather than skipping the signer check.
mkdir -p "$FIX/bin-noapksigner"
cp "$FIX/bin/gh" "$FIX/bin-noapksigner/gh"
for cmd in bash dirname tr cat; do
    ln -sf "$(command -v "$cmd")" "$FIX/bin-noapksigner/$cmd"
done
check_failure_message "missing apksigner fails" "apksigner is not on PATH" "$FIX/bin-noapksigner"
# Missing openssl fails rather than skipping the certificate check.
mkdir -p "$FIX/bin-noopenssl"
cp "$FIX/bin/gh" "$FIX/bin-noopenssl/gh"
cp "$FIX/bin/apksigner" "$FIX/bin-noopenssl/apksigner"
for cmd in bash dirname tr; do
    ln -sf "$(command -v "$cmd")" "$FIX/bin-noopenssl/$cmd"
done
check_failure_message "missing openssl fails" "openssl is not on PATH" "$FIX/bin-noopenssl"

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
