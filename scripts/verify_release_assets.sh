#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - consumer-side verification of a published release
# ==============================================================================
#
# WHAT THIS IS FOR
#
# The producers attest every artifact with Sigstore (see
# .github/actions/attest-inputs) and scripts/verify_attestation.sh checks those
# statements on the way INTO the APK. But nothing ever checked the artifact a
# user actually installs: the release APK on the releases page. An attestation
# nobody verifies is a sticker, not a control - a release job that uploaded a
# stale APK with a fresh bundle (or no bundle) would look identical to a good
# one from the releases page.
#
# This is the consumer half for releases: given a tag, download exactly what a
# user would download and verify it the way a user should - the full asset set,
# the checksum, the Sigstore statement, the signing certificate, the
# stable/pre-release state, and the fact that the release is not a draft.
#
# WHY IT FAILS CLOSED
#
# A missing asset, a checksum mismatch, a bundle that does not vouch for the
# APK, an APK signed by a different certificate, a missing gh, apksigner, or
# openssl, or a gh too old for `attestation` all fail loudly rather than
# degrading to "unverified but continuing". There is no continue here: a
# consumer that cannot verify must say so.
#
# USAGE
#   scripts/verify_release_assets.sh <tag> [output-dir]
#   scripts/verify_release_assets.sh --require-stable <tag> [output-dir]
#
# --require-stable is for the promotion path's post-promotion check. A normal
# consumer verification passes for a pre-release; a promotion request against a
# release still marked pre-release fails.
#
# The signer is compared with provenance/release-cert.sha256 from this checkout,
# not only with the downloadable fingerprint asset: a release that replaces both
# the APK and its fingerprint still fails against the repository trust anchor.
#
# Environment:
#   GITHUB_REPOSITORY   owner/name; required (the repo the release must belong
#                       to and the attestation must name).
# ==============================================================================

set -uo pipefail

REQUIRE_STABLE=0
if [ "${1:-}" = "--require-stable" ]; then
    REQUIRE_STABLE=1
    shift
elif [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    sed -n '2,43p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

TAG="${1:-}"
OUT_ARG="${2:-}"
REPO_NAME="${GITHUB_REPOSITORY:-}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRUSTED_FP_FILE="$REPO_ROOT/provenance/release-cert.sha256"

if [ -z "$TAG" ]; then
    echo "verify_release_assets: a release tag argument is required." >&2
    exit 2
fi
if [ "$#" -gt 2 ]; then
    echo "verify_release_assets: too many arguments." >&2
    exit 2
fi
if [ -z "$REPO_NAME" ]; then
    echo "verify_release_assets: GITHUB_REPOSITORY is required." >&2
    exit 2
fi
if [ ! -s "$TRUSTED_FP_FILE" ]; then
    echo "verify_release_assets: trusted fingerprint $TRUSTED_FP_FILE is missing." >&2
    exit 2
fi
TRUSTED_FP="$(tr -d '[:space:]' < "$TRUSTED_FP_FILE" | tr 'A-F' 'a-f')"
if [[ ! "$TRUSTED_FP" =~ ^[0-9a-f]{64}$ ]]; then
    echo "verify_release_assets: $TRUSTED_FP_FILE is not a SHA-256 fingerprint." >&2
    exit 2
fi
if ! command -v gh >/dev/null 2>&1; then
    echo "verify_release_assets: gh is not on PATH; cannot download or verify." >&2
    exit 2
fi
# Same probe as verify_attestation.sh: a gh without the subcommand would
# report every artifact "unattested" for the wrong reason.
if ! gh attestation verify --help >/dev/null 2>&1; then
    echo "verify_release_assets: this gh has no 'attestation' command." >&2
    echo "  found: $(gh --version 2>/dev/null | head -1 || echo 'unknown')" >&2
    exit 2
fi
if ! command -v apksigner >/dev/null 2>&1; then
    echo "verify_release_assets: apksigner is not on PATH; cannot verify the signing certificate." >&2
    echo "  Install the Android build-tools and put their directory on PATH." >&2
    exit 2
fi
if ! command -v openssl >/dev/null 2>&1; then
    echo "verify_release_assets: openssl is not on PATH; cannot verify the published certificate." >&2
    exit 2
fi

EXPECTED_APK="app-release-aligned.apk"
EXPECTED_SHA="$EXPECTED_APK.sha256"
EXPECTED_BUNDLE="apk.attestation.json"
EXPECTED_FILES=(
    "$EXPECTED_APK"
    "$EXPECTED_SHA"
    "sbom.spdx.json"
    "provenance.json"
    "$EXPECTED_BUNDLE"
    "LICENSE"
    "THIRDPARTY_NATIVE.md"
    "release-cert.pem"
    "release-cert.sha256"
)

if [ -n "$OUT_ARG" ]; then
    OUT="$OUT_ARG"
    mkdir -p "$OUT" || {
        echo "verify_release_assets: cannot create output directory $OUT." >&2
        exit 2
    }
    if [ -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
        echo "verify_release_assets: output directory $OUT is not empty." >&2
        exit 2
    fi
else
    OUT="$(mktemp -d "${TMPDIR:-/tmp}/release-verify.XXXXXX")" || {
        echo "verify_release_assets: cannot create a temporary directory." >&2
        exit 2
    }
fi

echo "============================================================"
echo " Release consumer verification                              "
echo "============================================================"
echo "  repository : $REPO_NAME"
echo "  tag        : $TAG"
echo "  dir        : $OUT"
echo ""

# --- 1. download exactly what a release page offers -------------------------
if ! gh release download "$TAG" --repo "$REPO_NAME" --dir "$OUT" >/dev/null 2>&1; then
    echo "[FAIL]   cannot download release $TAG from $REPO_NAME" >&2
    exit 1
fi

# --- 2. the full asset set ---------------------------------------------------
missing=()
for asset in "${EXPECTED_FILES[@]}"; do
    [ -s "$OUT/$asset" ] || missing+=("$asset")
done
if [ "${#missing[@]}" -ne 0 ]; then
    echo "[FAIL]   release is missing required asset(s):" >&2
    printf '           %s\n' "${missing[@]}" >&2
    exit 1
fi

for pattern in '*.apk' '*.apk.sha256' '*.attestation.json'; do
    count="$(find "$OUT" -maxdepth 1 -name "$pattern" -print 2>/dev/null | wc -l | tr -d '[:space:]')"
    if [ "$count" -ne 1 ]; then
        echo "[FAIL]   release must contain exactly one '$pattern' asset; found $count" >&2
        exit 1
    fi
done

echo "  apk        : $EXPECTED_APK"
echo "  manifest   : $EXPECTED_SHA"
echo "  bundle     : $EXPECTED_BUNDLE"
echo ""

# --- 3. byte identity against the published manifest -------------------------
if ! (cd "$OUT" && sha256sum -c --strict "$EXPECTED_SHA" >/dev/null 2>&1); then
    echo "[FAIL]   checksum manifest does not verify $EXPECTED_APK" >&2
    echo "           manifest: $(awk '{print $1}' "$OUT/$EXPECTED_SHA" | head -1)" >&2
    echo "           actual:   $(sha256sum "$OUT/$EXPECTED_APK" | awk '{print $1}')" >&2
    exit 1
fi
echo "  [OK]     checksum matches the published manifest"

# --- 4. origin: the shipped bundle must vouch for these exact bytes ---------
if gh attestation verify "$OUT/$EXPECTED_APK" --bundle "$OUT/$EXPECTED_BUNDLE" --repo "$REPO_NAME" >/dev/null 2>&1; then
    echo "  [OK]     Sigstore bundle vouches for this APK (repo: $REPO_NAME)"
else
    echo "[FAIL]   attestation does not vouch for this APK" >&2
    echo "         bundle: $EXPECTED_BUNDLE" >&2
    exit 1
fi

# --- 5. signing identity -----------------------------------------------------
if ! SIGNER_OUT="$(apksigner verify --print-certs "$OUT/$EXPECTED_APK" 2>&1)"; then
    echo "[FAIL]   apksigner rejected the APK signature" >&2
    printf '%s\n' "$SIGNER_OUT" | sed 's/^/         /' >&2
    exit 1
fi
SIGNER_COUNT="$(printf '%s\n' "$SIGNER_OUT" | grep -c 'certificate SHA-256 digest:' || true)"
if [ "$SIGNER_COUNT" -ne 1 ]; then
    echo "[FAIL]   expected exactly one signer certificate; found $SIGNER_COUNT" >&2
    exit 1
fi
APK_FP="$(printf '%s\n' "$SIGNER_OUT" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | head -1 | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
ASSET_FP="$(tr -d '[:space:]' < "$OUT/release-cert.sha256" | tr 'A-F' 'a-f')"
if [[ ! "$ASSET_FP" =~ ^[0-9a-f]{64}$ ]]; then
    echo "[FAIL]   release-cert.sha256 is not a SHA-256 certificate fingerprint" >&2
    exit 1
fi
if [ -z "$APK_FP" ]; then
    echo "[FAIL]   cannot read the signer certificate fingerprint from the APK" >&2
    exit 1
fi
if [ "$ASSET_FP" != "$TRUSTED_FP" ]; then
    echo "[FAIL]   published fingerprint does not match the repository trust anchor" >&2
    echo "           trusted:  $TRUSTED_FP" >&2
    echo "           release:  $ASSET_FP" >&2
    exit 1
fi
if [ "$APK_FP" != "$TRUSTED_FP" ]; then
    echo "[FAIL]   APK signer certificate does not match the repository trust anchor" >&2
    echo "           trusted:  $TRUSTED_FP" >&2
    echo "           apk:      $APK_FP" >&2
    exit 1
fi
CERT_FP="$(openssl x509 -in "$OUT/release-cert.pem" -noout -fingerprint -sha256 2>/dev/null \
    | sed 's/.*=//' | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
if [ -z "$CERT_FP" ]; then
    echo "[FAIL]   cannot read release-cert.pem" >&2
    exit 1
fi
if [ "$CERT_FP" != "$TRUSTED_FP" ]; then
    echo "[FAIL]   release-cert.pem does not match the repository trust anchor" >&2
    exit 1
fi
echo "  [OK]     APK signer, certificate, and fingerprint match the repository trust anchor"
echo "           $TRUSTED_FP"

# --- 6. release state --------------------------------------------------------
if ! RELEASE_STATE="$(gh release view "$TAG" --repo "$REPO_NAME" --json isDraft,isPrerelease --jq '[.isDraft, .isPrerelease] | @tsv' 2>/dev/null)"; then
    echo "[FAIL]   cannot read the GitHub Release state for $TAG" >&2
    exit 1
fi
IFS=$'\t' read -r RELEASE_DRAFT RELEASE_PRERELEASE <<< "$RELEASE_STATE"
if [ "$RELEASE_DRAFT" = "true" ]; then
    echo "[FAIL]   $TAG is still a draft release; publish it before verification" >&2
    exit 1
fi
case "$RELEASE_PRERELEASE" in
    true)  STATE_LABEL="pre-release" ;;
    false) STATE_LABEL="stable" ;;
    *)
        echo "[FAIL]   unexpected release state: $RELEASE_STATE" >&2
        exit 1
        ;;
esac
echo "  [OK]     release state: $STATE_LABEL"
if [ "$REQUIRE_STABLE" -eq 1 ] && [ "$RELEASE_PRERELEASE" != "false" ]; then
    echo "[FAIL]   $TAG is still a pre-release; stable promotion requires a stable release" >&2
    exit 1
fi

echo ""
echo "RESULT: release $TAG verifies as published by $REPO_NAME ($STATE_LABEL)."
