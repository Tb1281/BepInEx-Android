#!/usr/bin/env bash
# Fixture harness for scripts/verify_ci_artifacts.sh.
#
# Rebuilds the exact tree build-launcher-apk produces after "Install runtime
# artifacts": three artifacts under incoming/ plus their contents copied into
# jniLibs and assets. Each case then breaks one thing and asserts the gate
# notices it.
#
# The producers are replayed from their own working directories, because that is
# the reason their manifests disagree about paths: each job writes `sha256sum` from
# wherever it happens to be standing. A fixture that hashed everything from one
# root would not reproduce the input this gate has to read.
set -uo pipefail

# Temporary, and removed on exit, so a run on a CI runner does not leave a tree
# behind for the next one to trip over.
FIX="$(mktemp -d "${TMPDIR:-/tmp}/verify-ci-artifacts.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
REPO="$(cd "$(dirname "$0")/.." && pwd)"

build_clean() {
    rm -rf "$FIX"
    mkdir -p "$FIX/scripts"
    cp "$REPO/scripts/verify_ci_artifacts.sh" "$FIX/scripts/"

    JNI="$FIX/launcher/app/src/main/jniLibs/arm64-v8a"
    AS="$FIX/launcher/app/src/main/assets"
    mkdir -p "$JNI" "$AS"
    mkdir -p "$FIX/incoming/bepinex-managed" "$FIX/incoming/native-deps" \
             "$FIX/incoming/runtime-android"

    # --- staging: each producer's own tree, rooted where that job runs ---
    PB="$FIX/stage/bepinex/launcher/app/src/main/assets"
    PN="$FIX/stage/native/launcher/app/src/main/jniLibs/arm64-v8a"
    PR="$FIX/stage/runtime/out/runtime"
    mkdir -p "$PB" "$PN" "$PR/native"
    printf 'managed-framework\n' > "$PB/BepInEx.Android.zip"
    printf 'dobby-bytes\n' > "$PN/libdobby.so"
    printf 'pine-bytes\n'  > "$PN/libpine.so"
    printf 'coreclr\n' > "$PR/native/libcoreclr.so"
    printf 'clrjit\n'  > "$PR/native/libclrjit.so"
    printf 'pal\n'     > "$PR/native/libSystem.Security.Cryptography.Native.Android.so"
    printf 'bcl-zip\n' > "$PR/dotnet.zip"

    # --- each producer records its hashes, exactly as the workflow does ---
    ( cd "$FIX/stage/bepinex" \
      && sha256sum launcher/app/src/main/assets/BepInEx.Android.zip \
         > "$FIX/incoming/bepinex-managed/bepinex-managed.sha256" )
    ( cd "$PN" && sha256sum libdobby.so libpine.so \
         > "$FIX/incoming/native-deps/native-deps.sha256" )
    # shellcheck disable=SC2035  # mirrors fetch-runtime.yml's own `sha256sum *.so`
    ( cd "$PR/native" && sha256sum *.so > "$FIX/incoming/runtime-android/coreclr.sha256" )
    ( cd "$FIX/stage/runtime" && sha256sum out/runtime/dotnet.zip \
         >> "$FIX/incoming/runtime-android/coreclr.sha256" )

    # --- the consumer copies each artifact in, preserving its internal paths ---
    mkdir -p "$FIX/incoming/bepinex-managed/launcher/app/src/main/assets"
    cp "$PB/BepInEx.Android.zip" \
       "$FIX/incoming/bepinex-managed/launcher/app/src/main/assets/"
    mkdir -p "$FIX/incoming/native-deps/launcher/app/src/main/jniLibs/arm64-v8a"
    cp "$PN/"*.so "$FIX/incoming/native-deps/launcher/app/src/main/jniLibs/arm64-v8a/"
    mkdir -p "$FIX/incoming/runtime-android/out/runtime/native"
    cp "$PR/native/"*.so "$FIX/incoming/runtime-android/out/runtime/native/"
    cp "$PR/dotnet.zip" "$FIX/incoming/runtime-android/out/runtime/"

    # --- "Install runtime artifacts" ---
    cp "$FIX/incoming/bepinex-managed/launcher/app/src/main/assets/BepInEx.Android.zip" "$AS/"
    cp "$FIX/incoming/native-deps/launcher/app/src/main/jniLibs/arm64-v8a/"*.so "$JNI/"
    cp "$FIX/incoming/runtime-android/out/runtime/native/"*.so "$JNI/"
    cp "$FIX/incoming/runtime-android/out/runtime/dotnet.zip" "$AS/"
    # ...plus the NDK library, which no producer records
    printf 'ndk-libcpp\n' > "$JNI/libc++_shared.so"
}

expect() { # expect <want-exit> <label> <needle> [needle2]
    local want="$1" label="$2"
    shift 2
    local out rc needle
    out="$("$FIX/scripts/verify_ci_artifacts.sh" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        return 1
    fi
    for needle in "$@"; do
        if ! printf '%s' "$out" | grep -qF -- "$needle"; then
            echo "FAIL  $label: output did not mention '$needle'"
            printf '%s\n' "$out" | sed 's/^/        /'
            return 1
        fi
    done
    echo "ok    $label (exit $rc)"
    return 0
}

fails=0
run() { expect "$@" || fails=$((fails + 1)); }

echo "== verify_ci_artifacts.sh fixture =="

build_clean; run 0 "clean tree passes"                        "RESULT: PASSED" \
                 "[OK]       runtime-android: libSystem.Security.Cryptography.Native.Android.so"

build_clean; printf 'tampered\n' >> "$JNI/libcoreclr.so"
                  run 1 "tampered .so is caught"              "[MISMATCH] runtime-android: libcoreclr.so"
build_clean; printf 'tampered\n' >> "$AS/dotnet.zip"
                  run 1 "tampered dotnet.zip is caught"       "[MISMATCH] runtime-android: dotnet.zip"
build_clean; printf 'tampered\n' >> "$JNI/libpine.so"
                  run 1 "tampered native lib is caught"       "[MISMATCH] native-deps: libpine.so"
build_clean; printf 'tampered\n' >> "$AS/BepInEx.Android.zip"
                  run 1 "tampered managed framework is caught" "[MISMATCH] bepinex-managed: BepInEx.Android.zip"

# A swap between two files of the same producer: every file is present, every
# name is correct, every count matches, and both carry the other's bytes.
build_clean; cp "$JNI/libpine.so" "$JNI/libdobby.so"
                  run 1 "swapped bytes between two files"    "[MISMATCH] native-deps: libdobby.so"

build_clean; rm "$JNI/libclrjit.so"
                  run 1 "missing installed file is caught"    "[MISSING]  runtime-android: libclrjit.so"

build_clean; rm "$FIX/incoming/bepinex-managed/bepinex-managed.sha256"
                  run 1 "absent producer manifest fails"      "is absent"
build_clean; : > "$FIX/incoming/native-deps/native-deps.sha256"
                  run 1 "empty manifest fails"               "[EMPTY]"
build_clean; printf '# only a comment\n' > "$FIX/incoming/native-deps/native-deps.sha256"
                  run 1 "comment-only manifest fails"         "[EMPTY]"

# A producer starts shipping something new: it gets installed, and it must be
# listed before that is allowed to pass.
build_clean; printf 'sneaky\n' > "$FIX/incoming/runtime-android/out/runtime/native/libextra.so"
                  cp "$FIX/incoming/runtime-android/out/runtime/native/libextra.so" "$JNI/"
                  run 1 "producer-added .so must be listed"   "[UNLISTED]" "libextra.so"

build_clean; printf 'sneaky\n' > "$JNI/libunlisted.so"
                  run 1 "unattributed .so is caught"          "libunlisted.so"

# The NDK library is not attributed, and its absence is reported rather than
# passed over - the gate says what it did not cover.
build_clean; rm "$JNI/libc++_shared.so"
                  run 0 "NDK library absent still passes"     "RESULT: PASSED" \
                 "[N/A]      libc++_shared.so"

# A manifest path containing whitespace must be rejected, not truncated into a
# name that happens to exist.
build_clean
                  printf '%s  out/has space.zip\n' "$(printf '' | sha256sum | awk '{print $1}')" \
                     >> "$FIX/incoming/runtime-android/coreclr.sha256"
                  run 1 "unparseable entry is rejected"       "cannot parse entry"

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"