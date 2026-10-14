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
# user would download and verify it the way a user should - checksum first
# (byte identity against the published manifest), then the Sigstore statement
# for the APK against the shipped bundle (origin: this repo built these bytes).
#
# WHY IT FAILS CLOSED
#
# A missing asset, a checksum mismatch, a bundle that does not vouch for the
# APK, a missing gh, or a gh too old for `attestation` all fail loudly rather
# than degrading to "unverified but continuing". There is no continue here: a
# consumer that cannot verify must say so.
#
# USAGE
#   scripts/verify_release_assets.sh <tag> [output-dir]
#
# Environment:
#   GITHUB_REPOSITORY   owner/name; required (the repo the release must belong
#                       to and the attestation must name).
# ==============================================================================

set -uo pipefail

TAG="${1:-}"
OUT="${2:-$(mktemp -d "${TMPDIR:-/tmp}/release-verify.XXXXXX")}"
REPO_NAME="${GITHUB_REPOSITORY:-}"

if [ -z "$TAG" ]; then
    echo "verify_release_assets: a release tag argument is required." >&2
    exit 2
fi
if [ -z "$REPO_NAME" ]; then
    echo "verify_release_assets: GITHUB_REPOSITORY is required." >&2
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

mkdir -p "$OUT"

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

APK="$(find "$OUT" -maxdepth 1 -name "*.apk" | head -1)"
SHA="$(find "$OUT" -maxdepth 1 -name "*.apk.sha256" | head -1)"
BUNDLE="$(find "$OUT" -maxdepth 1 -name "*.attestation.json" | head -1)"

fails=0

if [ -z "$APK" ]; then
    echo "[FAIL]   release has no APK asset"
    fails=$((fails + 1))
fi
if [ -z "$SHA" ]; then
    echo "[FAIL]   release has no APK checksum manifest"
    fails=$((fails + 1))
fi
if [ -z "$BUNDLE" ]; then
    echo "[FAIL]   release has no attestation bundle"
    fails=$((fails + 1))
fi
[ "$fails" -eq 0 ] || exit 1

echo "  apk        : $(basename "$APK")"
echo "  manifest   : $(basename "$SHA")"
echo "  bundle     : $(basename "$BUNDLE")"
echo ""

# --- 2. byte identity against the published manifest -------------------------
want="$(awk '{print $1}' "$SHA" | head -1)"
got="$(sha256sum "$APK" | awk '{print $1}')"
if [ -z "$want" ]; then
    echo "[FAIL]   checksum manifest is empty"
    exit 1
fi
if [ "$want" = "$got" ]; then
    echo "  [OK]     checksum matches the published manifest"
else
    echo "[FAIL]   checksum mismatch:"
    echo "           manifest: $want"
    echo "           actual:   $got"
    exit 1
fi

# --- 3. origin: the shipped bundle must vouch for these exact bytes ---------
if gh attestation verify "$APK" --bundle "$BUNDLE" --repo "$REPO_NAME" >/dev/null 2>&1; then
    echo "  [OK]     Sigstore bundle vouches for this APK (repo: $REPO_NAME)"
else
    echo "[FAIL]   attestation does not vouch for this APK"
    echo "         bundle: $(basename "$BUNDLE")"
    exit 1
fi

echo ""
echo "RESULT: release $TAG verifies as published by $REPO_NAME."
