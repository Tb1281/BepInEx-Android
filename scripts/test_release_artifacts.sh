#!/usr/bin/env bash
# Fixture: the shipping APKs are release builds, and the Gradle cache is on.
#
# WHY THIS EXISTS
#
# Both workflows that assemble an APK used to run assembleDebug, so the
# release build type - R8 with the crypto PAL keep rules plus resource
# shrinking - never ran in CI, and the sync releases shipped debug APKs
# carrying every unused compose icon class (classes.dex alone was 39.6 MB of
# a 104 MB APK). A workflow edit that flips one back to debug, or a
# build.gradle.kts edit that quietly disables the minification the switch
# depends on, would not fail any build; it would just ship a fatter APK.
# The fixture asserts the build type, the aligned artifact name, the Gradle
# config it depends on, and the cache both Gradle jobs rely on.
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

WF1=".github/workflows/build-launcher-apk.yml"
WF2=".github/workflows/sync-upstream.yml"
GRADLE="launcher/app/build.gradle.kts"

echo "== release artifacts =="

check "build-launcher-apk assembles release" grep -q "assembleRelease" "$WF1"
check "sync-upstream assembles release" grep -q "assembleRelease" "$WF2"
check_absent "no assembleDebug in workflows" "assembleDebug" "$WF1" "$WF2"
check_absent "no debug APK filename in workflows" "app-debug" "$WF1" "$WF2"

# The aligned artifact name must be the same everywhere it is referenced:
# build it, check it, checksum it, attest it, upload/release it.
for wf in "$WF1" "$WF2"; do
    check "$(basename "$wf") names the release-aligned APK" \
        grep -q "app-release-aligned.apk" "$wf"
done

# The switch depends on the release build type staying minified: R8 with the
# keep rules is what makes the release APK distributable, and the crypto PAL
# resolves its Java classes by name, so a rule change here aborts the game
# process instead of failing the build. Comment lines are stripped first:
# the rationale comments inside buildTypes name the same settings.
check "release minification still enabled" \
    bash -c "sed -n '/buildTypes/,/^    }/p' \"$GRADLE\" | grep -v '^[[:space:]]*//' | grep -q 'isMinifyEnabled = true'"
check "release resource shrinking still enabled" \
    bash -c "sed -n '/buildTypes/,/^    }/p' \"$GRADLE\" | grep -v '^[[:space:]]*//' | grep -q 'isShrinkResources = true'"

# Both Gradle jobs cache dependencies (public repo quota); a workflow edit
# that drops the cache silently reverts to full downloads every run.
for wf in "$WF1" "$WF2"; do
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
