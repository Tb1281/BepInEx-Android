#!/usr/bin/env bash
# Build the native hook engines (dobby, LSPlant) for arm64-v8a from pinned sources.
#
# Nothing is downloaded and nothing is vendored: both libraries are compiled from
# the upstream commits pinned in provenance/dobby.env and provenance/lsplant.env,
# using the Android NDK.
#
# Source adaptations live in references/*.patch and are applied with `git apply`
# to a detached scratch copy, so a build never leaves a checkout dirty and the
# same patches are reviewable in a diff. The previous version used `sed` and
# `echo >>` against the submodule in place, which made a build non-repeatable and
# impossible to audit.
#
# Requires: Android NDK r25+ (r30 LTS recommended), CMake 3.30.3 (pinned by
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
# 3.30.3 is preferred: LSPlant requires >= 3.28, and 3.22.1 is kept only for
# the launcher Gradle build (which pins it in build.gradle.kts).
if [ -z "${CMAKE_BIN:-}" ]; then
    if [ -x "$NDK/../../cmake/3.30.3/bin/cmake" ]; then
        CMAKE_BIN="$NDK/../../cmake/3.30.3/bin/cmake"
    elif [ -x "$NDK/../../cmake/3.22.1/bin/cmake" ]; then
        CMAKE_BIN="$NDK/../../cmake/3.22.1/bin/cmake"
    else
        CMAKE_BIN="$(command -v cmake)"
    fi
fi
CMAKE_VERSION="$("$CMAKE_BIN" --version | head -1)"
# LSPlant's master snapshot (see provenance/lsplant.env) requires CMake
# 3.28+ for its C++ module build, so nothing older is accepted at all -
# the 3.22.1 fallback in the selection above exists only so the file can
# be found for the Gradle build's pin, never for this script to use.
# Moving between accepted versions rebaselines the manifest like any
# pin change.
case "$CMAKE_VERSION" in
    *" 3.2"[89]"."*|*" 3."[3-9]*|*" 4."*) ;;
    *) echo "refusing: $CMAKE_BIN reports '$CMAKE_VERSION'; need CMake >= 3.28 (pin: 3.30.3; set CMAKE_BIN to override)" >&2; exit 1 ;;
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
    # No --recurse-submodules: the clone would resolve submodules at the
    # default branch's gitlinks (the wrong tree until the pin is detached
    # below) and would fetch submodules no build consumes - LSPlant's test
    # submodules are SSH-addressed, which not every build host can reach.
    # Each pin file's declared nested submodules are fetched explicitly by
    # the blocks below, after checkout, at the pinned tree's gitlinks.
    git clone --quiet "$url" "$dst"

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

    # Nested submodules. The clone above does not fetch them; each one the pin
    # file names is fetched here, at the checked-out tree's gitlink, and asserted.
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

    # LSPlant's DexBuilder submodule, same hole and same fix as xz above: sync
    # submodules to the *checked-out* tree's gitlinks first (the clone resolved
    # them at the default branch), then assert both nested HEADs - DexBuilder
    # and its own parallel_hashmap - against the pin file.
    if grep -q '^LSPLANT_SUBMODULE_DEXBUILDER_COMMIT=' "$env_file" 2>/dev/null; then
        local dex_path phash_path dex_head phash_head
        dex_path="$(pin_field "$env_file" LSPLANT_SUBMODULE_DEXBUILDER_PATH)"
        phash_path="$(pin_field "$env_file" LSPLANT_SUBMODULE_PHASHMAP_PATH)"
        git -C "$dst" submodule update --quiet --init --recursive "$dex_path"
        dex_head="$(git -C "$dst/$dex_path" rev-parse HEAD)"
        phash_head="$(git -C "$dst/$phash_path" rev-parse HEAD)"
        if [ "$dex_head" != "$(pin_field "$env_file" LSPLANT_SUBMODULE_DEXBUILDER_COMMIT)" ]; then
            echo "ERROR: $name nested DexBuilder is $dex_head but is pinned to $(pin_field "$env_file" LSPLANT_SUBMODULE_DEXBUILDER_COMMIT)" >&2
            exit 1
        fi
        if [ "$phash_head" != "$(pin_field "$env_file" LSPLANT_SUBMODULE_PHASHMAP_COMMIT)" ]; then
            echo "ERROR: $name nested parallel_hashmap is $phash_head but is pinned to $(pin_field "$env_file" LSPLANT_SUBMODULE_PHASHMAP_COMMIT)" >&2
            exit 1
        fi
        echo "  DexBuilder pinned ${dex_head:0:10}, parallel_hashmap pinned ${phash_head:0:10}"
    fi
    return 0
}

# Android 15 / API 35 requires every LOAD segment to be 16KB aligned.
# In NDK r28+ (including r30 LTS), the 64-bit linker defaults to 16KB alignment (0x4000).
# The max-page-size linker flags are explicitly injected below as a defensive safeguard
# to ensure subproject CMake scripts do not override the target page size.
cmake_configure() {
    local name="$1" src="$2"; shift 2
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
        "$@" \
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
    # debug section a project's own flags may emit (measured on the previous
    # Pine build; libdobby ships none and liblsplant is stripped by its own
    # post-build step, so both are unaffected). AGP strips at packaging
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
# LSPlant — the ART hook framework the launcher uses for the Java side.
# The pin is an upstream master snapshot: v6.4 predates upstream's Android
# 17 support and cannot initialize on the target ART. One source patch (see
# provenance/lsplant.env): the JIT code cache collection hook is non-fatal,
# because that ART removed both entry points upstream knows. The two extra
# CMake definitions are build inputs, not adaptations: ANDROID_STL=c++_static
# keeps the std::function-based InitInfo ABI consistent with
# libhookengine.so, and DEBUG_SYMBOLS_PATH is where upstream's post-build
# step writes the unstripped symbols before stripping the shipped .so.
# ---------------------------------------------------------------------------
echo "==> lsplant"
stage lsplant "$ROOT/provenance/lsplant.env"
git -C "$WORK/lsplant" apply --3way "$ROOT/$(pin_field "$ROOT/provenance/lsplant.env" LSPLANT_PATCH_FILE)"
# LSPlant master builds its C++ modules against the `cxx` CMake package
# (org.lsposed.libcxx, a prefab AAR whose version tracks the NDK). Gradle
# consumers get the package from AGP's prefab support; this raw-CMake build
# materializes the same package from the pinned AAR: an imported cxx::cxx
# target plus the AAR's own prefab/cmake-config.cmake, which registers the
# std module providers exactly as it does under AGP.
LIBCXX_VERSION="$(pin_field "$ROOT/provenance/lsplant.env" LSPLANT_LIBCXX_VERSION)"
LIBCXX_AAR="$BUILD/libcxx-$LIBCXX_VERSION.aar"
if [ ! -f "$LIBCXX_AAR" ]; then
    curl -fsSL -o "$LIBCXX_AAR" \
        "https://repo1.maven.org/maven2/org/lsposed/libcxx/libcxx/$LIBCXX_VERSION/libcxx-$LIBCXX_VERSION.aar"
fi
echo "$(pin_field "$ROOT/provenance/lsplant.env" LSPLANT_LIBCXX_SHA256)  $LIBCXX_AAR" | sha256sum -c - >/dev/null
rm -rf "$WORK/libcxx" "$WORK/libcxx-cmake"
mkdir -p "$WORK/libcxx" "$WORK/libcxx-cmake"
unzip -q -o "$LIBCXX_AAR" -d "$WORK/libcxx"
LIBCXX_MODULE="$WORK/libcxx/prefab/modules/cxx"
cat > "$WORK/libcxx-cmake/cxxConfig.cmake" <<EOF
if(NOT TARGET cxx::cxx)
    add_library(cxx::cxx STATIC IMPORTED GLOBAL)
    set_target_properties(cxx::cxx PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "$LIBCXX_MODULE/include"
        IMPORTED_LOCATION "$LIBCXX_MODULE/libs/android.arm64-v8a/libcxx.a")
    set_property(TARGET cxx::cxx PROPERTY INTERFACE_LINK_LIBRARIES "")
    include("$LIBCXX_MODULE/include/prefab/cmake-config.cmake")
endif()
EOF
cmake_configure lsplant "$WORK/lsplant/$(pin_field "$ROOT/provenance/lsplant.env" LSPLANT_CMAKE_SOURCE_SUBDIR)" \
    -DANDROID_STL=c++_static \
    -Dcxx_DIR="$WORK/libcxx-cmake" \
    -DDEBUG_SYMBOLS_PATH="$BUILD/lsplant/symbols"
install_so lsplant liblsplant.so

echo
echo "Installed into $OUT:"
ls -la "$OUT/libdobby.so" "$OUT/liblsplant.so"