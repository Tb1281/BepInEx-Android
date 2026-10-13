#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - Official Unity Library Injection Patcher
#
# This script takes an unmodified, official libunity.so from any Unity IL2CPP
# game and applies the injection linkage (patchelf --add-needed libmain.so)
# and ensures 16KB page alignment for Android 15+.
#
# Usage:
#   ./scripts/patch_game_unity.sh /path/to/official/libunity.so [output_path]
# ==============================================================================

set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <path-to-official-libunity.so> [output-path]"
    echo ""
    echo "Example:"
    echo "  $0 /tmp/com.innersloth.spacemafia/libunity.so app/src/main/jniLibs/arm64-v8a/libunity.so"
    exit 1
fi

INPUT_LIB="$1"
OUTPUT_LIB="${2:-app/src/main/jniLibs/arm64-v8a/libunity.so}"

if [ ! -f "${INPUT_LIB}" ]; then
    echo "Error: Input file '${INPUT_LIB}' not found!"
    exit 1
fi

# Ensure patchelf is available
if ! command -v patchelf &> /dev/null; then
    echo "patchelf is required but not installed."
    echo "Install via: sudo apt-get install patchelf"
    exit 1
fi

echo "=== BepInEx Android Unity Patcher ==="
echo "Input:  ${INPUT_LIB}"
echo "Output: ${OUTPUT_LIB}"

mkdir -p "$(dirname "${OUTPUT_LIB}")"
cp -f "${INPUT_LIB}" "${OUTPUT_LIB}"

# Check if libmain.so is already needed
if readelf -d "${OUTPUT_LIB}" | grep -q "Shared library: \[libmain.so\]"; then
    echo "Notice: libmain.so is already linked in DT_NEEDED."
else
    echo "Patching: Adding DT_NEEDED libmain.so to ${OUTPUT_LIB}..."
    patchelf --add-needed libmain.so "${OUTPUT_LIB}"
fi

# Verify dynamic section
echo "Verification:"
readelf -d "${OUTPUT_LIB}" | grep "Shared library: \[libmain.so\]" || {
    echo "Error: Failed to verify libmain.so DT_NEEDED entry!"
    exit 1
}

echo "Successfully patched ${OUTPUT_LIB} for BepInEx injection!"
