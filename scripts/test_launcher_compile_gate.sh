#!/usr/bin/env bash
# Fixture: the launcher compile gate in verify-provenance.yml is wired up.
#
# WHY THIS EXISTS
#
# The gate itself is a Gradle invocation (`./gradlew assembleDebug`), not a
# script, so there is no unit under it to fixture the way every other gate
# here has one. What broke it once already was never the compiler - it was
# the wiring around it:
#
#   - 267ec5b broke GameResourceContext.kt and main stayed green, because no
#     automatic job compiled the launcher at all (build-launcher-apk.yml is
#     workflow_dispatch only). The compile step added for that must keep
#     running on launcher changes: if the trigger lists drop the launcher
#     paths, the gate silently stops covering the code it exists to check.
#   - the step must run AFTER the staging step: it compiles against the
#     jniLibs/assets staged from the pins in the same job. Moved before
#     staging, it would compile against stale leftovers and prove nothing.
#   - the provenance job blocks egress with an explicit allow-list. Gradle
#     needs its distribution, the plugin portal, and the dependency repos
#     (settings.gradle.kts names google(), mavenCentral(), gradlePluginPortal()
#     plus the aliyun mirrors and jitpack). A host dropped from the list
#     fails every run under `block`, not just broken ones - the pilot looks
#     red for a reason no diff explains.
#
# So this asserts the wiring statically (stdlib only, like
# test_harden_runner.sh): the compile step exists after staging, both trigger
# lists watch the launcher inputs, and the pilot allow-list names the Gradle
# hosts.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

WF=".github/workflows/verify-provenance.yml"
fails=0

# 1. The compile step exists and runs after staging.
compile_line="$(grep -n "gradlew assembleDebug" "$WF" | head -1 | cut -d: -f1)"
stage_line="$(grep -n "stage_launcher_inputs.sh out/runtime" "$WF" | head -1 | cut -d: -f1)"
if [ -z "$compile_line" ]; then
    echo "FAIL  no gradlew assemble step in $WF"
    fails=$((fails + 1))
elif [ -z "$stage_line" ] || [ "$compile_line" -le "$stage_line" ]; then
    echo "FAIL  compile step (line ${compile_line:-?}) is not after staging (line ${stage_line:-?})"
    fails=$((fails + 1))
else
    echo "ok    compile step follows staging (lines $stage_line -> $compile_line)"
fi

# 2. Both trigger lists watch the launcher inputs (test_workflow_triggers.sh
#    proves the lists agree; here we prove they cover the gate's inputs).
for path in "launcher/app/src/main/**" "launcher/settings.gradle.kts" \
            "launcher/gradle.properties" "launcher/build.gradle.kts"; do
    n="$(grep -cF -- "- '$path'" "$WF" || true)"
    if [ "$n" -eq 2 ]; then
        echo "ok    trigger lists watch $path (push + pull_request)"
    else
        echo "FAIL  $path appears $n time(s) in trigger lists, want 2"
        fails=$((fails + 1))
    fi
done

# 3. The provenance pilot allow-list names the Gradle/Maven hosts. Scoped to
#    the provenance job (patch-queue pilots a smaller list without them).
prov_block="$(awk '/^  provenance:/{inj=1;next} /^  patch-queue:/{inj=0} inj' "$WF")"
for host in services.gradle.org downloads.gradle.org plugins.gradle.org \
            maven.google.com maven.aliyun.com jitpack.io; do
    if printf '%s' "$prov_block" | grep -qF -- "$host"; then
        echo "ok    allow-list names $host"
    else
        echo "FAIL  allow-list is missing $host"
        fails=$((fails + 1))
    fi
done
if printf '%s' "$prov_block" | grep -qE "repo\.maven\.apache\.org|repo1\.maven\.org"; then
    echo "ok    allow-list names a Maven Central host"
else
    echo "FAIL  allow-list names no Maven Central host"
    fails=$((fails + 1))
fi

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
