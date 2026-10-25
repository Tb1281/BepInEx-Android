#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - CI prebuilt fetcher
#
# Installs the native runtime and managed framework artifacts produced by THIS
# repository's CI into app/src/main/jniLibs/arm64-v8a and app/src/main/assets.
#
# There is deliberately no default remote. A prebuilt archive is only trusted when
# it comes from your own build pipeline; set RELEASE_BASE_URL to the mirror that
# hosts your build-native-deps / fetch-runtime output. Every installed file is then
# checked against provenance/sha256sums.txt, so a mismatched archive is rejected
# rather than silently installed.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${ROOT_DIR}/.." && pwd)"

JNI_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
CACHE_DIR="${ROOT_DIR}/build/prebuilts_cache"
ARCHIVE_NAME="bepinex_android_prebuilts.tar.gz"

mkdir -p "${JNI_DIR}" "${ASSETS_DIR}" "${CACHE_DIR}"

echo "============================================================"
echo " BepInEx Android - CI prebuilt fetcher                      "
echo "============================================================"

# Local cache always wins: re-running the fetcher must not re-download.
ARCHIVE_PATH="${CACHE_DIR}/${ARCHIVE_NAME}"

if [ ! -f "${ARCHIVE_PATH}" ]; then
    if [ -z "${RELEASE_BASE_URL:-}" ]; then
        echo "[INFO] No cached archive at ${ARCHIVE_PATH}"
        echo "[INFO] and RELEASE_BASE_URL is unset."
        echo ""
        echo "       Populate the tree from CI output instead:"
        echo "         .github/workflows/build-native-deps.yml  -> libdobby.so, liblsplant.so"
        echo "         .github/workflows/fetch-runtime.yml      -> libcoreclr.so, PAL shims, dotnet.zip"
        echo "         .github/workflows/build-bepinex.yml       -> BepInEx.Android.zip"
        echo ""
        echo "       or point RELEASE_BASE_URL at your own mirror:"
        echo "         RELEASE_BASE_URL=https://... ./scripts/download_prebuilts.sh"
        echo ""
        echo "[INFO] Continuing to verification of whatever is already in the tree."
    else
        echo "[INFO] Downloading ${RELEASE_BASE_URL}/${ARCHIVE_NAME}"
        if command -v curl &> /dev/null; then
            curl -fSL "${RELEASE_BASE_URL}/${ARCHIVE_NAME}" -o "${ARCHIVE_PATH}"
        elif command -v wget &> /dev/null; then
            wget -q "${RELEASE_BASE_URL}/${ARCHIVE_NAME}" -O "${ARCHIVE_PATH}"
        else
            echo "[ERROR] Neither curl nor wget is available." >&2
            exit 1
        fi
        echo "[INFO] Extracting ${ARCHIVE_NAME}"
        tar -xzf "${ARCHIVE_PATH}" -C "${ROOT_DIR}"
    fi
fi

echo ""
"${SCRIPT_DIR}/verify_prebuilts.sh"
echo ""
"${SCRIPT_DIR}/verify_crypto_pal.sh"