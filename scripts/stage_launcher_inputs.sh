#!/usr/bin/env bash
# Stage locally built runtime inputs into the launcher tree for a Gradle build.
#
# Mirrors the "Install runtime artifacts" step of
# .github/workflows/build-launcher-apk.yml, but reads from local producer
# outputs instead of downloaded CI artifacts. Fails closed on any missing
# input: a partial staging would ship a half-old APK.
#
# Usage:
#   ./scripts/stage_launcher_inputs.sh <runtime-dir> <framework-zip>
#
#   <runtime-dir>    output of ./scripts/fetch_dotnet_runtime.sh
#                    (expects native/*.so and dotnet.zip inside)
#   <framework-zip>  output of ./scripts/package_bepinex_android.sh
#                    (out/BepInEx.Android.zip)
#
# Hook engines (libdobby.so, liblsplant.so) are written straight into jniLibs by
# ./scripts/build_native_deps.sh, so they are only asserted here, not copied.
# libc++_shared.so comes from ANDROID_NDK_HOME (or the pinned NDK under
# ANDROID_HOME), mirroring the CI install step.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="${1:?usage: stage_launcher_inputs.sh <runtime-dir> <framework-zip>}"
FRAMEWORK_ZIP="${2:?usage: stage_launcher_inputs.sh <runtime-dir> <framework-zip>}"
JNI="$ROOT/launcher/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/launcher/app/src/main/assets"

for f in "$RUNTIME_DIR/native/libcoreclr.so" "$RUNTIME_DIR/dotnet.zip" "$FRAMEWORK_ZIP"; do
    test -f "$f" || { echo "[FAIL] missing input: $f" >&2; exit 1; }
done
for f in "$JNI/libdobby.so" "$JNI/liblsplant.so"; do
    test -f "$f" || { echo "[FAIL] missing hook engine: $f (run scripts/build_native_deps.sh)" >&2; exit 1; }
done

NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}"
LIBCXX="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
test -f "$LIBCXX" || { echo "[FAIL] NDK libc++ not found: $LIBCXX" >&2; exit 1; }

mkdir -p "$JNI" "$ASSETS"
cp "$RUNTIME_DIR"/native/*.so "$JNI/"
cp "$RUNTIME_DIR/dotnet.zip" "$ASSETS/dotnet.zip"
cp "$FRAMEWORK_ZIP" "$ASSETS/BepInEx.Android.zip"
cp "$LIBCXX" "$JNI/"

echo "staged:"
ls "$JNI" "$ASSETS"
echo "run launcher/scripts/verify_prebuilts.sh to check against the manifest"
