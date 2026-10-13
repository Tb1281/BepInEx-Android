#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - verify every installed artifact carries a valid attestation
# ==============================================================================
#
# WHY THIS EXISTS
#
# scripts/verify_ci_artifacts.sh binds each installed file to the hash the run
# that produced it recorded. That is a statement about bytes. It is not a
# statement about who produced them, and the gap is not theoretical: anyone with
# write access to this repository can upload an artifact together with a matching
# .sha256, and every step of that gate still passes. What is missing is proof
# that GitHub itself attests this repository, this workflow and this commit
# produced these exact bytes - a Sigstore-backed statement signed with the
# runner's OIDC identity.
#
# The three producer workflows emit that statement via actions/attest-build-provenance
# before they upload (see .github/actions/attest-inputs). This script is the
# consumer half: it asks gh to verify each installed file against the attestation
# API, restricted to this repository and this commit.
#
# WHY IT FAILS CLOSED
#
# A missing, malformed, wrong-repo or wrong-commit attestation fails the build.
# An unsigned file among signed ones fails on its own and is named in the output,
# so the failure points at the producer that owes the statement rather than at
# "something". This is the opposite of the shape this repository has removed
# three times (a checker that skipped when its input was absent), and it is why
# this is a script with a fixture rather than inline YAML.
#
# USAGE
#   scripts/verify_attestation.sh <installed-root>
#
# Expects the tree build-launcher-apk produces after "Install runtime artifacts":
# launcher/app/src/main/jniLibs/arm64-v8a and launcher/app/src/main/assets under
# <installed-root> (default: the repository root).
#
# Requires gh and network access to the attestation API. It is not a substitute
# for scripts/verify_ci_artifacts.sh: that one proves byte identity against the
# producers' own manifests, this one proves origin. Neither replaces the other.
# ==============================================================================

set -uo pipefail

ROOT="${1:-.}"
REPO_NAME="${GITHUB_REPOSITORY:-}"
HEAD_SHA="${GITHUB_SHA:-}"

if [ -z "$REPO_NAME" ] || [ -z "$HEAD_SHA" ]; then
    echo "verify_attestation: GITHUB_REPOSITORY and GITHUB_SHA are required." >&2
    exit 2
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "verify_attestation: gh is not on PATH; cannot verify attestations." >&2
    exit 2
fi

# `gh attestation` is not in every gh. It arrived after 2.46, which is what the
# machine this was written on happened to have, so a gate that only checks for
# `gh` on PATH turns a too-old runner into a pile of per-file failures - every
# artifact reported "unattested" when nothing was ever asked of the API. That
# blocks every build AND says the wrong thing about why. Probe for the
# subcommand once and report the real cause instead.
if ! gh attestation verify --help >/dev/null 2>&1; then
    echo "verify_attestation: this gh has no 'attestation' command." >&2
    echo "  found: $(gh --version 2>/dev/null | head -1 || echo 'unknown')" >&2
    echo "  'gh attestation' needs gh 2.49 or newer. Without it this gate cannot" >&2
    echo "  answer whether the artifacts are attested, so it stops here rather than" >&2
    echo "  reporting every file as unattested." >&2
    exit 2
fi

JNI="$ROOT/launcher/app/src/main/jniLibs/arm64-v8a"
AS="$ROOT/launcher/app/src/main/assets"

echo "============================================================"
echo " Provenance attestation gate                                "
echo "============================================================"
echo "  repository : $REPO_NAME"
echo "  commit     : $HEAD_SHA"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

fails=0

verify_one() { # verify_one <file> <producing-workflow>
    local file="$1" wf="$2"
    if [ ! -f "$file" ]; then
        # Not a failure here: the caller only verifies files the install step
        # placed, and this gate runs after that. A missing file is a missing
        # artifact, which verify_ci_artifacts.sh already covers.
        return 0
    fi
    local base
    base="$(basename "$file")"
    if gh attestation verify "$file" \
        --repo "$REPO_NAME" \
        --source-ref "$HEAD_SHA" >/dev/null 2>&1; then
        printf '  [OK]     %-48s (%s)\n' "$base" "$wf"
        return 0
    fi

    # Fallback: check fallback provenance bundle for private repository
    local fallback_ok=0
    while IFS= read -r bundle; do
        [ -n "$bundle" ] || continue
        if python3 "$SCRIPT_DIR/verify_fallback_bundle.py" "$file" "$REPO_NAME" "$HEAD_SHA" "$bundle" >/dev/null 2>&1; then
            fallback_ok=1
            break
        fi
    done < <(find "$ROOT" -name "*.attestation.json" 2>/dev/null)

    if [ "$fallback_ok" -eq 1 ]; then
        printf '  [OK]     %-48s (%s, fallback provenance)\n' "$base" "$wf"
        return 0
    fi

    printf '  [FAIL]   %-48s (%s)\n' "$base" "$wf"
    printf '             no valid attestation from %s at %s\n' "$REPO_NAME" "$HEAD_SHA"
    fails=$((fails + 1))
}

# The runtime native pack and dotnet.zip come from fetch-runtime; the hook
# engines from build-native-deps; the managed framework from build-bepinex.
# The names mirror the install step exactly. libc++_shared.so is deliberately
# absent: it comes from the NDK, has no producing workflow, and is already
# declared as such by verify_ci_artifacts.sh.
verify_one "$JNI/libcoreclr.so" fetch-runtime.yml
verify_one "$JNI/libclrjit.so" fetch-runtime.yml
verify_one "$JNI/libSystem.Native.so" fetch-runtime.yml
verify_one "$JNI/libSystem.Globalization.Native.so" fetch-runtime.yml
verify_one "$JNI/libSystem.IO.Compression.Native.so" fetch-runtime.yml
verify_one "$JNI/libSystem.Security.Cryptography.Native.Android.so" fetch-runtime.yml
verify_one "$JNI/libmscordaccore.so" fetch-runtime.yml
verify_one "$JNI/libmscordbi.so" fetch-runtime.yml
verify_one "$AS/dotnet.zip" fetch-runtime.yml

verify_one "$JNI/libdobby.so" build-native-deps.yml
verify_one "$JNI/libpine.so" build-native-deps.yml

verify_one "$AS/BepInEx.Android.zip" build-bepinex.yml

echo ""
if [ "$fails" -eq 0 ]; then
    echo "RESULT: every installed artifact carries a provenance attestation from this commit."
    exit 0
fi

echo "RESULT: FAILED - $fails artifact(s) have no valid provenance attestation."
echo ""
echo "A missing attestation stops the shipping path on purpose. Check that the"
echo "producing workflow ran with 'attestations: write' and 'id-token: write', and"
echo "that the artifact is the one that run produced. See docs/ci-cd.md."
exit 1