#!/usr/bin/env bash
# Fixture: the APK build paths use the right variant, and only non-release jobs
# use the Gradle cache.
#
# WHY THIS EXISTS
#
# Both non-tag APK workflows used to run assembleDebug, so the release build
# type - R8 with the crypto PAL keep rules plus resource shrinking - never ran
# in CI, and they shipped debug APKs carrying every unused compose icon class
# (classes.dex alone was 39.6 MB of a 104 MB APK). The switch to the release
# build type fixed that, but a later edit could quietly flip one back, disable
# minification, or point the tag workflow at the artifact variant.
#
# Release Contract v1 adds a second distinction: only the tag workflow may
# build the release variant, because only it has the release key. The manual
# launcher build and the daily upstream sync build the artifact variant, which
# keeps the same shrinking and alignment but is debug-signed under a distinct
# application ID and is never published. This fixture asserts both.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

fails=0
check() { # check <name> <command...>
    local name="$1"; shift
    if "$@" >/dev/null 2>&1; then
        echo "ok    $name"
    else
        echo "FAIL  $name"
        fails=$((fails + 1))
    fi
}
check_absent() { # check_absent <name> <pattern> <file...>
    local name="$1" pattern="$2"; shift 2
    if grep -rq "$pattern" "$@"; then
        echo "FAIL  $name: '$pattern' still present"
        fails=$((fails + 1))
    else
        echo "ok    $name"
    fi
}

WF_RELEASE=".github/workflows/release-tag.yml"
WF_SHIP=".github/workflows/ship-release.yml"
WF_VERIFY=".github/workflows/verify-release.yml"
WF_PROMOTE=".github/workflows/promote-release.yml"
WF_LAUNCHER=".github/workflows/build-launcher-apk.yml"
WF_SYNC=".github/workflows/sync-upstream.yml"
GRADLE="launcher/app/build.gradle.kts"

echo "== release and artifact builds =="

check "release-tag assembles the release variant" grep -q "assembleRelease" "$WF_RELEASE"
check "release-tag names the release-aligned APK" grep -q "app-release-aligned.apk" "$WF_RELEASE"
check "release-tag runs the release preflight" grep -q "release_preflight.sh" "$WF_RELEASE"
# Tag names can contain shell metacharacters. Passing the tag through the
# environment keeps `${{ github.ref_name }}` out of a `run:` body that holds the
# release key; quoting the environment value is safe.
check "release-tag passes the preflight tag from the environment" \
    grep -q 'release_preflight.sh "\$RELEASE_TAG"' "$WF_RELEASE"
check "release-tag does not interpolate the tag into run" \
    bash -c '! grep -q "release_preflight.sh \"\${{ github.ref_name }}\"" "$1"' _ "$WF_RELEASE"
check "release-tag passes the tag version to Gradle" grep -q "bepinex.versionName" "$WF_RELEASE"
# zipalign rewrites the archive and invalidates the v2/v3 signature AGP just
# applied. Signing must happen after alignment, and the build must prove the
# signer before the APK can reach the shipping job.
check "release-tag aligns before signing" \
    bash -c 'z=$(grep -n "ZIPALIGN" "$1" | head -1 | cut -d: -f1); a=$(grep -n "apksigner.*sign" "$1" | head -1 | cut -d: -f1); [ -n "$z" ] && [ -n "$a" ] && [ "$a" -gt "$z" ]' _ "$WF_RELEASE"
check "release-tag verifies the release signer" \
    grep -q 'verify --print-certs' "$WF_RELEASE"
check "release-tag compares the signer with the release certificate" \
    grep -q 'provenance/release-cert.sha256' "$WF_RELEASE"
# The uploaded path is nested under the repository root, so the ship job must
# be told the same path; the flat default cannot find it after download.
check "release-tag passes the nested APK path to the ship job" \
    grep -q "apk_path: launcher/app/build/outputs/apk/release/app-release-aligned.apk" "$WF_RELEASE"
check "release-tag is limited to v[0-9] tags" \
    grep -qF -- "- 'v[0-9]*'" "$WF_RELEASE"
check "release-tag declares the release environment" \
    grep -q "^    environment: release$" "$WF_RELEASE"
# The release job is the one job with the key. A cache restore/save path there
# is a second state channel into that job; the contract keeps it absent.
check_absent "release-tag does not use the Gradle cache" "actions/cache" "$WF_RELEASE"
check_absent "release-tag trigger does not match verified" "v\\*'$" "$WF_RELEASE"
check_absent "release-tag is not branch-triggered" "^    branches:" "$WF_RELEASE"
check_absent "release-tag is not manually dispatched" "^  workflow_dispatch:" "$WF_RELEASE"
check_absent "ship-release does not get the release environment" "environment: release" "$WF_SHIP"
check "ship-release is reusable only" grep -q "^  workflow_call:" "$WF_SHIP"
check_absent "ship-release is not push-triggered" "^  push:" "$WF_SHIP"
check_absent "ship-release is not release-triggered" "^  release:" "$WF_SHIP"
check_absent "ship-release has no release key" "secrets.RELEASE_" "$WF_SHIP"

# The shipping path publishes the complete contract asset set and keeps the
# tag-only creation guarantee.
for asset in "THIRDPARTY_NATIVE.md" "release-cert.pem" "release-cert.sha256" "LICENSE"; do
    check "ship-release publishes $asset" grep -q "$asset" "$WF_SHIP"
done
check "ship-release publishes the APK checksum asset" grep -q '"\$APK_PATH.sha256"' "$WF_SHIP"
check "ship-release publishes LICENSE, notices, cert, and fingerprint" \
    grep -q 'LICENSE THIRDPARTY_NATIVE.md release-cert.pem release-cert.sha256' "$WF_SHIP"
check "ship-release prints flat checksum names for downloaded assets" \
    grep -q 'sha256sum "$(basename "$SBOM_PATH")"' "$WF_SHIP"
check "ship-release verifies the pushed tag" grep -q -- "--verify-tag" "$WF_SHIP"
check "ship-release can create a pre-release" grep -q -- "--prerelease" "$WF_SHIP"
for section in "Install and upgrade" "Verified device matrix" "Known issues" "Checksums" "Signing certificate" "Attestation" "License and source"; do
    check "ship-release notes include $section" grep -q "$section" "$WF_SHIP"
done
check "ship-release notes name the one-time uninstall" \
    grep -q "com.bepinex.android.release" "$WF_SHIP"
check "ship-release notes name the release package" \
    grep -q "installs as .com.bepinex.android." "$WF_SHIP"
check "ship-release notes list The Tower crash" \
    grep -q "Firebase RTDB" "$WF_SHIP"
check "ship-release notes list the Pine review" \
    grep -q "Pine license review" "$WF_SHIP"
check "ship-release notes state GPL and LGPL" \
    bash -c 'grep -q "GPL-3.0" "$1" && grep -q "LGPL-2.1" "$1"' _ "$WF_SHIP"

# The consumer job must install apksigner; the runner image does not ship it.
check "verify-release installs pinned build-tools" \
    grep -q 'build-tools;35.0.0' "$WF_VERIFY"
check "verify-release checks out the default-branch verifier" \
    grep -q 'ref: .*github.event.repository.default_branch' "$WF_VERIFY"
check "verify-release passes the tag through the environment" \
    grep -q 'RELEASE_TAG: .*github.event.release.tag_name' "$WF_VERIFY"

# Promotion is manual and never receives the release key.
check "promote-release is workflow_dispatch only" grep -q "workflow_dispatch:" "$WF_PROMOTE"
check_absent "promote-release is not release-triggered" "^  release:" "$WF_PROMOTE"
check_absent "promote-release is not push-triggered" "^  push:" "$WF_PROMOTE"
check "promote-release checks out the default-branch verifier" \
    grep -q 'ref: .*github.event.repository.default_branch' "$WF_PROMOTE"
check_absent "promote-release does not get the release environment" "environment: release" "$WF_PROMOTE"
check "promote-release requires recorded device verification" \
    grep -q "device_verification" "$WF_PROMOTE"
check "promote-release installs pinned build-tools" \
    grep -q 'build-tools;35.0.0' "$WF_PROMOTE"
check "promote-release uses the verify-only preflight" \
    grep -q -- "--verify-only" "$WF_PROMOTE"
check "promote-release requires the stable verifier after editing" \
    grep -q -- "--require-stable" "$WF_PROMOTE"
check "promote-release clears the pre-release flag" \
    grep -q -- "--prerelease=false" "$WF_PROMOTE"
check "promote-release can restore the pre-release flag" \
    grep -q -- "gh release edit .* --prerelease$" "$WF_PROMOTE"
check_absent "promote-release has no release key" "secrets.RELEASE_" "$WF_PROMOTE" "$WF_VERIFY"

for wf in "$WF_LAUNCHER" "$WF_SYNC"; do
    check "$(basename "$wf") assembles the artifact variant" grep -q "assembleArtifact" "$wf"
    check "$(basename "$wf") names the artifact-aligned APK" grep -q "app-artifact-aligned.apk" "$wf"
done
check_absent "non-tag workflows do not assemble release" "assembleRelease" "$WF_LAUNCHER" "$WF_SYNC"
check_absent "no assembleDebug in workflows" "assembleDebug" "$WF_LAUNCHER" "$WF_SYNC"
check_absent "no debug APK filename in workflows" "app-debug" "$WF_LAUNCHER" "$WF_SYNC"

# The sync path is an artifact producer, not a release path. Removing the
# release/Sigstore egress entries is part of that contract: this job neither
# mints an attestation nor creates or uploads a release.
check_absent "sync-upstream does not call the shipping path" "ship-release.yml" "$WF_SYNC"
check_absent "sync-upstream has no release trigger" "^  release:" "$WF_SYNC"
check_absent "sync-upstream is not push-triggered" "^  push:" "$WF_SYNC"
check_absent "sync-upstream is not tag-triggered" "^    tags:" "$WF_SYNC"
check_absent "sync-upstream does not create releases" "gh release create" "$WF_SYNC"
check_absent "sync-upstream has no release environment" "environment: release" "$WF_SYNC"
check_absent "sync-upstream reads no release secrets" "secrets.RELEASE_" "$WF_SYNC"
check_absent "sync-upstream does not attest for publication" "attestations: write" "$WF_SYNC"
check_absent "sync-upstream does not request an OIDC token" "id-token: write" "$WF_SYNC"
check_absent "sync-upstream has no release asset endpoint" "release-assets.githubusercontent.com" "$WF_SYNC"
check_absent "sync-upstream has no release upload endpoint" "uploads.github.com" "$WF_SYNC"
check_absent "sync-upstream has no release helper endpoint" "cafe.github.com" "$WF_SYNC"
check_absent "sync-upstream has no Fulcio endpoint" "fulcio.sigstore.dev" "$WF_SYNC"
check_absent "sync-upstream has no Rekor endpoint" "rekor.sigstore.dev" "$WF_SYNC"
check_absent "sync-upstream does not create a pre-release" "prerelease" "$WF_SYNC"

# Release jobs request only the permissions they use.
check "verify-release has read-only contents" grep -q "contents: read" "$WF_VERIFY"
check_absent "verify-release cannot write contents" "contents: write" "$WF_VERIFY"
check_absent "verify-release cannot attest" "attestations: write" "$WF_VERIFY"
check_absent "verify-release cannot mint OIDC" "id-token: write" "$WF_VERIFY"
check "promote-release writes contents for the release edit" grep -q "contents: write" "$WF_PROMOTE"
check_absent "promote-release cannot attest" "attestations: write" "$WF_PROMOTE"
check_absent "promote-release cannot mint OIDC" "id-token: write" "$WF_PROMOTE"
check "ship-release writes contents" grep -q "contents: write" "$WF_SHIP"
check "ship-release attests" grep -q "attestations: write" "$WF_SHIP"
check "ship-release mints OIDC" grep -q "id-token: write" "$WF_SHIP"
check "ship-release can dispatch the consumer verifier" grep -q "actions: write" "$WF_SHIP"
check "release-tag caller grants the ship job Actions write" \
    bash -c 'sed -n "/^  ship:/,\$p" "$1" | grep -q "actions: write"' _ "$WF_RELEASE"
check "ship-release dispatches verify-release with the tag" \
    grep -q 'gh workflow run verify-release.yml --repo "$GITHUB_REPOSITORY" --ref main -f tag="$RELEASE_TAG"' "$WF_SHIP"
check_absent "ship-release cannot write issues" "issues: write" "$WF_SHIP"
check "release-tag build has read-only contents" \
    bash -c 'sed -n "/^  build:/,/^  ship:/p" "$1" | grep -q "contents: read"' _ "$WF_RELEASE"
check_absent "release-tag build cannot write contents" "contents: write" \
    <(sed -n '/^  build:/,/^  ship:/p' "$WF_RELEASE")
check_absent "release-tag build cannot attest" "attestations: write" \
    <(sed -n '/^  build:/,/^  ship:/p' "$WF_RELEASE")
check_absent "release-tag build cannot mint OIDC" "id-token: write" \
    <(sed -n '/^  build:/,/^  ship:/p' "$WF_RELEASE")

# Only release-tag may read the release key. This catches a future workflow
# that copies the secrets block without the environment boundary.
for wf in .github/workflows/*.yml; do
    case "$wf" in
        *release-tag.yml) continue ;;
    esac
    check_absent "$(basename "$wf") reads no release secrets" "secrets.RELEASE_" "$wf"
done

# The artifact variant must inherit the release build type's shrinking so the
# CI APK stays representative of the release bytes, while signing with debug
# under its own application ID.
check "artifact build type inherits release" \
    bash -c "sed -n '/create(\"artifact\")/,/^        }/p' \"$GRADLE\" | grep -q 'initWith(getByName(\"release\"))'"
check "artifact build type has its own application ID" \
    bash -c "sed -n '/create(\"artifact\")/,/^        }/p' \"$GRADLE\" | grep -q 'applicationIdSuffix = \".artifact\"'"

# The switch depends on the release build type staying minified: R8 with the
# keep rules is what makes the APK distributable, and the crypto PAL resolves
# its Java classes by name, so a rule change here aborts the game process
# instead of failing the build. Comment lines are stripped first: the rationale
# comments inside buildTypes name the same settings.
check "release minification still enabled" \
    bash -c "sed -n '/buildTypes/,/^    }/p' \"$GRADLE\" | grep -v '^[[:space:]]*//' | grep -q 'isMinifyEnabled = true'"
check "release resource shrinking still enabled" \
    bash -c "sed -n '/buildTypes/,/^    }/p' \"$GRADLE\" | grep -v '^[[:space:]]*//' | grep -q 'isShrinkResources = true'"

# Both Gradle jobs cache dependencies (public repo quota); a workflow edit
# that drops the cache silently reverts to full downloads every run.
for wf in "$WF_LAUNCHER" "$WF_SYNC"; do
    check "$(basename "$wf") caches Gradle" \
        grep -q '~/.gradle/caches' "$wf"
done

if [ "$fails" -ne 0 ]; then
    echo ""
    echo "$fails CASE(S) FAILED"
    exit 1
fi
echo ""
echo "ALL FIXTURE CASES PASSED"
