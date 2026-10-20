#!/usr/bin/env bash
# Fixture: the APK build paths use the right variant, and the Gradle cache is on.
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
WF_LAUNCHER=".github/workflows/build-launcher-apk.yml"
WF_SYNC=".github/workflows/sync-upstream.yml"
GRADLE="launcher/app/build.gradle.kts"

echo "== release and artifact builds =="

check "release-tag assembles the release variant" grep -q "assembleRelease" "$WF_RELEASE"
check "release-tag names the release-aligned APK" grep -q "app-release-aligned.apk" "$WF_RELEASE"
check "release-tag runs the release preflight" grep -q "release_preflight.sh" "$WF_RELEASE"
check "release-tag passes the tag version to Gradle" grep -q "bepinex.versionName" "$WF_RELEASE"
# The uploaded path is nested under the repository root, so the ship job must
# be told the same path; the flat default cannot find it after download.
check "release-tag passes the nested APK path to the ship job" \
    grep -q "apk_path: launcher/app/build/outputs/apk/release/app-release-aligned.apk" "$WF_RELEASE"

for wf in "$WF_LAUNCHER" "$WF_SYNC"; do
    check "$(basename "$wf") assembles the artifact variant" grep -q "assembleArtifact" "$wf"
    check "$(basename "$wf") names the artifact-aligned APK" grep -q "app-artifact-aligned.apk" "$wf"
done
check_absent "non-tag workflows do not assemble release" "assembleRelease" "$WF_LAUNCHER" "$WF_SYNC"
check_absent "no assembleDebug in workflows" "assembleDebug" "$WF_LAUNCHER" "$WF_SYNC"
check_absent "no debug APK filename in workflows" "app-debug" "$WF_LAUNCHER" "$WF_SYNC"
check_absent "sync-upstream does not call the shipping path" "ship-release.yml" "$WF_SYNC"

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
