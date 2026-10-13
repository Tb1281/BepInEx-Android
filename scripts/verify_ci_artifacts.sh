#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - verify the CI-installed artifacts against the producer's hashes
# ==============================================================================
#
# WHY THIS EXISTS
#
# provenance/sha256sums.txt describes the binaries committed to the repository.
# The CI shipping path does not use those bytes. build-launcher-apk downloads
# three artifacts from three separate runs, copies them into the tree, and
# builds from those. Nothing compared those files to anything.
#
# Each producer job did record a hash next to its output - bepinex-managed.sha256,
# coreclr.sha256, native-deps.sha256 - and each of those files travels inside the
# artifact it belongs to. The consumer downloaded all three and then never read
# them. build-launcher-apk's own comment said so plainly:
#
#   "The committed manifest describes the committed artifacts. CI-produced
#    artifacts legitimately differ, so the checksum attestation for this build
#    is recorded below instead."
#
# Recording is not checking. A hash that nothing is compared against cannot
# fail, so it was decoration on the one path that actually ships. This script is
# the comparison. Nothing about the producers changed: the hashes were already
# being produced and shipped; only the consumer's half was missing.
#
# WHAT IT DOES NOT COVER, stated so nobody assumes otherwise
#
#   - The bytes inside the packaged APK. AGP strips native libraries while
#     packaging, so for libdobby.so, libpine.so and libc++_shared.so the shipped
#     bytes are NOT the bytes recorded here - measured, in docs/progress.md
#     under "AGP가 패키징 때 네이티브 3종을 strip한다": libpine.so goes from
#     926,544 bytes to 71,912. This gate covers the inputs to packaging, not the
#     package. It is still worth having: it is the last point at which a mismatch
#     is attributable to a named producing run rather than to packaging, and the
#     only place a substituted artifact is compared to anything at all.
#
#   - libfusion.so and libmain.so. The Gradle/CMake build produces those here,
#     so no producer job exists to have hashed them. They are covered by the
#     `zipalign -c -P 16 4` check on the packaged APK instead.
#
#   - libc++_shared.so. It comes from the NDK, not from any of the three jobs, so
#     no producer manifest names it and this script does not require it to be
#     named. It is reported as unattributed rather than silently passed.
#
#   - Origin. These hashes bind a file to the run that produced it. They are not
#     a statement about who ran it. build-launcher-apk already checks that all
#     three runs were built from this commit; the two checks compose and neither
#     replaces the other. See provenance/SOURCES.md for what the manifest as a
#     whole does and does not prove.
# ==============================================================================
#
# USAGE
#   scripts/verify_ci_artifacts.sh [incoming-dir]
#
# Expects the layout build-launcher-apk produces after "Install runtime
# artifacts": the three artifacts under `incoming/` (default) and their contents
# copied into launcher/app/src/main/{jniLibs/arm64-v8a,assets}.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INCOMING="${1:-incoming}"
JNI="launcher/app/src/main/jniLibs/arm64-v8a"
ASSETS="launcher/app/src/main/assets"

# Counted rather than exiting on the first failure: a substituted artifact set
# is usually more than one file, and a run that reports only the first mismatch
# sends someone back to CI to discover the others one job at a time.
FAILURES=0

# Every file name the three producers claim, accumulated across them. The
# attribution check at the end asks whether anything installed is missing from
# this set, which is the shape a substituted binary takes: a file that arrived
# from no producer and is therefore compared to nothing.
ATTRIBUTED=""

echo "============================================================"
echo " CI artifact verification                                "
echo " incoming: ${INCOMING}"
echo "============================================================"

# The three producers record the same kind of thing in three different path
# conventions, because each writes from the directory it happened to stand in:
#
#   bepinex-managed.sha256 : launcher/app/src/main/assets/BepInEx.Android.zip
#   native-deps.sha256     : libdobby.so, libpine.so
#   coreclr.sha256        : libcoreclr.so, ... then out/runtime/dotnet.zip
#
# The consumer, though, installs by kind: every .so into jniLibs, every archive
# into assets. That mapping is total and unambiguous, so entries are resolved by
# file name and placed by extension. It is deliberately not a search: resolving an
# entry by "look for this name somewhere in the tree" would let a file that
# arrived from the wrong producer satisfy an entry meant for another.
#
# Emitted as <hash>TAB<basename>. A tab keeps the two fields unambiguous even
# though neither may contain whitespace in practice.
parse_manifest() {
    local file="$1"
    if [ ! -f "$file" ]; then
        # stderr, for the same reason as the awk diagnostics below.
        echo "::error::${file} is absent. Every producer job must record what it built;" >&2
        echo "::error::if a job stopped recording checksums, that is a change to this gate, not to it." >&2
        return 1
    fi
    # sha256sum writes "<hash><two spaces><path>"; awk collapses the run of
    # whitespace so $1 is the hash and $2 the path. A path containing whitespace
    # would parse as extra fields, which is rejected rather than silently read as
    # a truncated name - the three producers emit no such path today, and a
    # future one that does should fail loudly instead of verifying a wrong file.
    #
    # Diagnostics go to stderr, not stdout. The caller captures stdout in a
    # command substitution, so a reason written there would be swallowed and the
    # gate would fail without saying why - which is the one outcome that costs
    # whoever hits it the most time.
    awk '
        function complain(msg) { printf "%s\n", msg | "cat 1>&2" }
        NF == 0    { next }
        $1 ~ /^#/  { next }
        NF != 2    {
            complain("::error::cannot parse entry in " FILENAME ": " $0)
            bad = 1
            next
        }
        {
            n = split($2, parts, "/")
            printf "%s\t%s\n", $1, parts[n]
        }
        END { if (bad) exit 1 }
    ' "$file"
}

check_entry() {
    # check_entry <expected-hash> <basename> <label>
    local expected="$1" base="$2" label="$3"
    local dir="$JNI"
    case "$base" in
        *.zip) dir="$ASSETS" ;;
    esac
    local path="$dir/$base"

    if [ ! -f "$path" ]; then
        echo "  [MISSING]  ${label}: ${base} is not installed at ${dir}"
        FAILURES=$((FAILURES + 1))
        return
    fi
    local actual
    actual="$(sha256sum "$path" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        echo "  [MISMATCH] ${label}: ${base}"
        echo "             expected ${expected}"
        echo "             actual   ${actual}"
        FAILURES=$((FAILURES + 1))
        return
    fi
    echo "  [OK]       ${label}: ${base}"
}

# Everything a producer shipped must be named in its manifest. Without this the
# gate would only say "the files I know about are correct", and a producer that
# gains an output would have it installed without ever being compared.
require_all_listed() {
    # require_all_listed <manifest-basenames> <source-dir> <label>
    local bases="$1" src="$2" label="$3"
    local path base
    for path in "$src"/*; do
        [ -f "$path" ] || continue
        base="$(basename "$path")"
        if ! printf '%s\n' "$bases" | grep -qxF -- "$base"; then
            echo "  [UNLISTED] ${label}: ${base} was installed from ${src} but the"
            echo "             ${label} manifest does not record it"
            FAILURES=$((FAILURES + 1))
        fi
    done
}

verify_one() {
    # verify_one <label> <manifest-path> <source-dir> [<source-dir> ...]
    local label="$1" manifest="$2"
    shift 2

    echo ""
    echo "[${label}] ${manifest}"
    local entries
    if ! entries="$(parse_manifest "$manifest")"; then
        # Counted, not merely reported. A producer that stopped emitting its
        # manifest must not leave the consumer with nothing to compare against -
        # that is the same fail-open shape this gate exists to remove, and it was
        # a bug here until the fixture caught it.
        echo "  [FAILED]   ${label}: manifest could not be read"
        FAILURES=$((FAILURES + 1))
        return
    fi
    if [ -z "$entries" ]; then
        echo "  [EMPTY]    ${label}: manifest records nothing, so it verifies nothing"
        echo "::error::${label} produced a checksum manifest with no entries"
        FAILURES=$((FAILURES + 1))
        return
    fi

    local bases
    bases="$(printf '%s\n' "$entries" | cut -f2)"
    ATTRIBUTED="${ATTRIBUTED}${bases}"$'\n'

    local expected base
    while IFS=$'\t' read -r expected base; do
        check_entry "$expected" "$base" "$label"
    done <<<"$entries"

    local src
    for src in "$@"; do
        require_all_listed "$bases" "$src" "$label"
    done
}

verify_one "bepinex-managed" \
    "${INCOMING}/bepinex-managed/bepinex-managed.sha256" \
    "${INCOMING}/bepinex-managed/launcher/app/src/main/assets"

verify_one "native-deps" \
    "${INCOMING}/native-deps/native-deps.sha256" \
    "${INCOMING}/native-deps/launcher/app/src/main/jniLibs/arm64-v8a"

verify_one "runtime-android" \
    "${INCOMING}/runtime-android/coreclr.sha256" \
    "${INCOMING}/runtime-android/out/runtime/native" \
    "${INCOMING}/runtime-android/out/runtime"

# An .so in jniLibs that no producer job accounts for is the shape a substituted
# binary takes, so this fails rather than warns. libc++_shared.so is the one
# legitimate exception and is named explicitly: "not mentioned" and "verified"
# are different states and only one of them is safe to assume.
echo ""
echo "[attribution] installed libraries with no producer manifest"
UNATTRIBUTED=""
for path in "$JNI"/*.so; do
    [ -f "$path" ] || continue
    base="$(basename "$path")"
    [ "$base" = "libc++_shared.so" ] && continue
    if ! printf '%s' "$ATTRIBUTED" | grep -qxF -- "$base"; then
        UNATTRIBUTED="${UNATTRIBUTED} ${base}"
    fi
done
if [ -n "$UNATTRIBUTED" ]; then
    echo "::error::these installed libraries are recorded by no producer job:${UNATTRIBUTED}"
    echo "::error::an .so in jniLibs that no job accounts for is exactly the shape of a"
    echo "::error::substituted binary, so this fails rather than warns."
    FAILURES=$((FAILURES + 1))
else
    echo "  [OK]       every installed library is accounted for by a producing job"
fi
echo "  [N/A]      libc++_shared.so comes from the NDK and has no producing job"

echo ""
echo "============================================================"
if [ "$FAILURES" -ne 0 ]; then
    echo " RESULT: FAILED"
    echo "   failures : ${FAILURES}"
    echo "============================================================"
    exit 1
fi
echo " RESULT: PASSED - every installed artifact matches its producer."
echo "============================================================"