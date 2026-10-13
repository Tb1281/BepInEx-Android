#!/usr/bin/env bash
# Build the native hook engines (dobby, Pine) for arm64-v8a from pinned sources.
#
# Nothing is downloaded and nothing is vendored: both libraries are compiled from
# the upstream commits pinned in provenance/dobby.env and provenance/pine.env,
# using the Android NDK.
#
# Source adaptations live in references/*.patch and are applied with `git apply`
# to a detached scratch copy, so a build never leaves a checkout dirty and the
# same patches are reviewable in a diff. The previous version used `sed` and
# `echo >>` against the submodule in place, which made a build non-repeatable and
# impossible to audit.
#
# Requires: Android NDK r25+ (r30 LTS recommended), CMake 3.22.1 (pinned by
# this script; see the CMAKE_BIN selection below), a Linux x86_64 host,
# and network access to github.com - the sources are cloned from their upstream
# URLs, not read out of third_party/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/launcher/app/src/main/jniLibs/arm64-v8a"
BUILD="$ROOT/build/deps"
WORK="$BUILD/src"

API="${API_LEVEL:-28}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
JOBS="${JOBS:-$(nproc)}"
# dobby's CMakeLists declares cmake_minimum_required(VERSION 3.4), which CMake 4
# refuses outright. Relaxing the policy floor keeps the upstream source untouched.
CMAKE_POLICY_VERSION_MINIMUM="${CMAKE_POLICY_VERSION_MINIMUM:-3.5}"

[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "NDK not found at $NDK; set ANDROID_NDK_HOME" >&2
    exit 1
}

# CMake is pinned like the NDK. Different CMake releases lay out sections
# differently, so an unpinned `cmake` turns byte-identical sources into
# different .so files (measured: 3.31.5 output differs from 3.22.1 output from
# the first section offset on, with an identical compiler). Prefer the
# SDK-pinned CMake next to the NDK; override with CMAKE_BIN for a deliberate
# toolchain move, which then rebaselines the manifest like any pin change.
if [ -z "${CMAKE_BIN:-}" ]; then
    if [ -x "$NDK/../../cmake/3.22.1/bin/cmake" ]; then
        CMAKE_BIN="$NDK/../../cmake/3.22.1/bin/cmake"
    else
        CMAKE_BIN="$(command -v cmake)"
    fi
fi
CMAKE_VERSION="$("$CMAKE_BIN" --version | head -1)"
case "$CMAKE_VERSION" in
    *" 3.22."*) ;;
    *) echo "refusing: $CMAKE_BIN reports '$CMAKE_VERSION'; need CMake 3.22.x (set CMAKE_BIN to override)" >&2; exit 1 ;;
esac
echo "cmake: $CMAKE_BIN ($CMAKE_VERSION)"
LLVM_STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$LLVM_STRIP" ] || { echo "llvm-strip not found in $NDK" >&2; exit 1; }

mkdir -p "$OUT" "$WORK"

# ---------------------------------------------------------------------------
# Pin reading.
#
# The pin lives in provenance/*.env - the same file check_upstream_pins.sh
# reports drift on. Reading it here instead of from a gitlink is what makes the
# two agree: one pin per dependency, not one per representation.
#
# Sourced in a subshell per call, because these files carry more than the pin and
# some of those names are shared across them (monomod.env and il2cppinterop.env
# both define LOCAL_FEED). One shell for all five would let the last one win.
# ---------------------------------------------------------------------------
pin_field() { # pin_field <env-file> <VARIABLE>
    local env_file="$1" var="$2"
    [ -f "$env_file" ] || { echo "missing pin file: $env_file" >&2; exit 1; }
    (
        set -a; . "$env_file"; set +a
        : "${!var:?$var missing from $env_file}"
        printf '%s' "${!var}"
    )
}

# ---------------------------------------------------------------------------
# Fetch a pinned upstream into a scratch directory and apply its adaptation
# patches.
#
# The post-clone HEAD assertion is load-bearing, not a nicety. A gitlink is
# verified by the checkout that fetches it: the git plumbing must produce that
# exact commit or the checkout fails. A URL clone retrieves whatever the remote
# currently serves, so the pin would otherwise be an unchecked assertion - a
# rewritten or hijacked branch, or a commit that merely looks like the pin,
# would be built and shipped under a manifest asserting it is the pinned tree.
#
# This is the same assertion sync-upstream.yml already applies to the submodule,
# for the same reason verify_prebuilts.sh compares against committed hashes.
# ---------------------------------------------------------------------------
stage() {
    local name="$1" env_file="$2"; shift 2
    local url commit dst head
    url="$(pin_field "$env_file" UPSTREAM_URL)"
    commit="$(pin_field "$env_file" UPSTREAM_COMMIT)"
    dst="$WORK/$name"

    rm -rf "$dst"
    echo "  cloning $url at ${commit:0:10}"
    git clone --quiet --recurse-submodules "$url" "$dst"

    # The commit has to be present in the clone before checkout is attempted.
    # Without this check a pin that names a commit the remote no longer serves
    # fails as "fatal: unable to read tree", which reads like a corrupt clone
    # rather than the thing it is: a pin that does not exist upstream.
    if ! git -C "$dst" cat-file -e "${commit}^{commit}" 2>/dev/null; then
        echo "ERROR: $name pin ${commit} is not present in $url" >&2
        echo "ERROR: the pin names a commit upstream does not serve" >&2
        echo "ERROR: refusing to build; re-pin with a commit that exists" >&2
        exit 1
    fi

    git -C "$dst" checkout --quiet --detach "$commit"

    head="$(git -C "$dst" rev-parse HEAD)"
    if [ "$head" != "$commit" ]; then
        echo "ERROR: $name cloned $head but is pinned to $commit" >&2
        echo "ERROR: refusing to build; this is not the pinned tree" >&2
        exit 1
    fi

    # Nested submodules. --recurse-submodules already resolved these to whatever
    # the remote serves, so each one the pin file names is re-pinned and re-asserted.
    # Pine compiles xz-embedded's C sources straight into libpine.so, which makes
    # that pin part of what this build produces - otherwise the checked-out commit
    # is identical between two runs while the compiled .so can differ, and no hash
    # in this repository would catch it, because the hash is of the top-level commit.
    if grep -q '^PINE_SUBMODULE_XZ_COMMIT=' "$env_file" 2>/dev/null; then
        local sub_commit sub_path sub_head
        sub_commit="$(pin_field "$env_file" PINE_SUBMODULE_XZ_COMMIT)"
        sub_path="$dst/core/src/main/cpp/external/xz-embedded"
        git -C "$dst" submodule update --quiet --init --force "$sub_path" >/dev/null 2>&1 || true
        git -C "$sub_path" fetch --quiet origin "$sub_commit" >/dev/null 2>&1 || true
        git -C "$sub_path" checkout --quiet --detach "$sub_commit"
        sub_head="$(git -C "$sub_path" rev-parse HEAD)"
        if [ "$sub_head" != "$sub_commit" ]; then
            echo "ERROR: $name nested xz-embedded is $sub_head but is pinned to $sub_commit" >&2
            exit 1
        fi
        echo "  xz-embedded pinned ${sub_commit:0:10}"
    fi
    return 0
}

# Android 15 / API 35 requires every LOAD segment to be 16KB aligned.
# In NDK r28+ (including r30 LTS), the 64-bit linker defaults to 16KB alignment (0x4000).
# The max-page-size linker flags are explicitly injected below as a defensive safeguard
# to ensure subproject CMake scripts do not override the target page size.
cmake_configure() {
    local name="$1" src="$2"
    local bdir="$BUILD/$name"
    rm -rf "$bdir"
    # Ninja when available, otherwise whatever generator CMake defaults to. Both
    # produce identical objects; the choice is a local build-speed matter only.
    local gen=()
    if [ -n "${CMAKE_GENERATOR:-}" ]; then
        gen=(-G "$CMAKE_GENERATOR")
    elif command -v ninja >/dev/null; then
        gen=(-GNinja)
    fi
    # -ffile-prefix-map through the toolchain's own extension point. The NDK
    # toolchain resets CMAKE_C/CXX_FLAGS (wiping environment CFLAGS), then
    # prepends ANDROID_COMPILER_FLAGS(_CXX), so -D is the only channel that
    # survives. This rewrites the absolute scratch path out of __FILE__
    # expansions in log strings, which otherwise bake the checkout location
    # into .rodata and make every build directory produce different bytes.
    "$CMAKE_BIN" -S "$src" -B "$bdir" \
        "${gen[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_COMPILER_FLAGS="-ffile-prefix-map=$WORK/=" \
        -DANDROID_COMPILER_FLAGS_CXX="-ffile-prefix-map=$WORK/=" \
        "${gen[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        ${CMAKE_POLICY_VERSION_MINIMUM:+-DCMAKE_POLICY_VERSION_MINIMUM="$CMAKE_POLICY_VERSION_MINIMUM"}
    "$CMAKE_BIN" --build "$bdir" -j"$JOBS"
}

install_so() {
    local name="$1" so="$2"
    local found
    found="$(find "$BUILD/$name" -name "$so" -type f | head -1)"
    if [ -z "$found" ]; then
        echo "ERROR: $so was not produced by the $name build" >&2
        exit 1
    fi
    cp "$found" "$OUT/$so"
    # Strip debug info only (symtab stays). DWARF carries the absolute build
    # directory (DW_AT_comp_dir), so unstripped objects differ per checkout
    # even with -ffile-prefix-map, which covers __FILE__ but not every
    # debug section a project's own flags may emit (measured on libpine;
    # libdobby ships none and is unaffected). AGP strips at packaging
    # anyway, so nothing at runtime can observe the difference.
    "$LLVM_STRIP" -g "$OUT/$so"
    echo "  $so <- ${found#$ROOT/}"
}

# ---------------------------------------------------------------------------
# Dobby — the inline hook engine behind libfusion's hook/unhook exports.
# ---------------------------------------------------------------------------
echo "==> dobby"
# The patch is named in provenance/dobby.env (DOBBY_PATCH_FILE) for the same reason
# Pine's two are: the pin file is the single record of what is adapted.
stage dobby "$ROOT/provenance/dobby.env"
echo "  applying $(basename "$(pin_field "$ROOT/provenance/dobby.env" DOBBY_PATCH_FILE)")"
git -C "$WORK/dobby" apply --3way "$ROOT/$(pin_field "$ROOT/provenance/dobby.env" DOBBY_PATCH_FILE)"
cmake_configure dobby "$WORK/dobby"
install_so dobby libdobby.so

# ---------------------------------------------------------------------------
# Pine — the ART hook framework the launcher uses for the Java side.
# Two upstream sources need adjusting for the NDK toolchain; both are declared as
# patches rather than inline edits.
# ---------------------------------------------------------------------------
echo "==> pine"
# Both Pine patches are named in provenance/pine.env (PINE_PATCH_FILES), read one
# per line so the pin file stays the single record of what is adapted and in what
# order. stage() runs first and clones once; the patches are then applied to that
# checkout. Reading the list from the pin file rather than hardcoding it here is
# what keeps "what Pine is adapted by" in one place.
stage pine "$ROOT/provenance/pine.env"
while IFS= read -r p; do
    [ -n "$p" ] || continue
    echo "  applying $(basename "$p")"
    git -C "$WORK/pine" apply --3way "$ROOT/$p"
done <<EOF
$(pin_field "$ROOT/provenance/pine.env" PINE_PATCH_FILES)
EOF
cmake_configure pine "$WORK/pine/core/src/main/cpp"
install_so pine libpine.so

echo
echo "Installed into $OUT:"
ls -la "$OUT/libdobby.so" "$OUT/libpine.so"