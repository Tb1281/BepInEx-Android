#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - release preflight
# ==============================================================================
#
# WHAT THIS IS FOR
#
# Release Contract v1 says a distributed APK comes from a tag on main, is
# covered by the moving `verified` tag, carries a monotonic version, and is
# signed by the release key. Those are five independent checks, and every one
# of them fails closed: a release that cannot prove all five does not build.
#
# This is the single gate that owns those checks. The tag workflow calls it
# before the expensive build; the promotion path calls it with --verify-only
# (no key needed) before a release is allowed to stop being a pre-release.
#
# WHAT IT CHECKS
#
#   1. The tag is vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-<label>, and is not
#      the moving `verified` tag.
#   2. The tag commit is an ancestor of the remote default branch.
#   3. The tag commit is covered by the remote `verified` tag. The local ref is
#      never authoritative: `verified` moves, and a stale fetch would otherwise
#      advertise coverage that no longer exists.
#   4. The derived versionCode is greater than the highest stable tag on the
#      remote, and no stable GitHub Release already exists for the tag. The
#      remote tag list is authoritative, not the local clone or the current
#      pre-release state of a GitHub Release.
#   5. The four release-key values are present, the keystore opens, and its
#      certificate fingerprint matches the committed fingerprint. --verify-only
#      skips this step because promotion has no key.
#
# USAGE
#   scripts/release_preflight.sh <tag>
#   scripts/release_preflight.sh --verify-only <tag>
#
# Environment:
#   GITHUB_REPOSITORY              owner/name; required in build mode to reject
#                                  an already-stable release for the tag.
#   RELEASE_KEYSTORE_BASE64        base64 PKCS12/JKS keystore (build mode).
#   RELEASE_KEYSTORE_PASSWORD      keystore password (build mode).
#   RELEASE_KEY_ALIAS              key alias (build mode).
#   RELEASE_KEY_PASSWORD           key password (build mode).
#   PREFLIGHT_KEYSTORE_OUT         optional path for the materialized keystore.
#   PREFLIGHT_REMOTE               git remote, default origin.
#   PREFLIGHT_DEFAULT_BRANCH       default branch, default main.
#   PREFLIGHT_CERT_FILE            public certificate, default provenance/release-cert.pem.
#   PREFLIGHT_FINGERPRINT_FILE     fingerprint, default provenance/release-cert.sha256.
#   GITHUB_OUTPUT                  if set, version_name/version_code/is_prerelease/
#                                  keystore_path are appended for the caller.
# ==============================================================================

set -uo pipefail
umask 077

REMOTE="${PREFLIGHT_REMOTE:-origin}"
DEFAULT_BRANCH="${PREFLIGHT_DEFAULT_BRANCH:-main}"
CERT_FILE="${PREFLIGHT_CERT_FILE:-provenance/release-cert.pem}"
FINGERPRINT_FILE="${PREFLIGHT_FINGERPRINT_FILE:-provenance/release-cert.sha256}"
VERIFY_ONLY=0
TAG=""
KEYSTORE=""
KEEP_KEYSTORE=0

die() {
    echo "release_preflight: $*" >&2
    exit 1
}

usage() {
    sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'
}

cleanup() {
    if [ "$KEEP_KEYSTORE" -eq 0 ] && [ -n "$KEYSTORE" ] && [ -f "$KEYSTORE" ]; then
        rm -f -- "$KEYSTORE"
    fi
}
trap cleanup EXIT

while [ "$#" -gt 0 ]; do
    case "$1" in
        --verify-only) VERIFY_ONLY=1; shift ;;
        --help|-h) usage; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option: $1" ;;
        *)
            if [ -n "$TAG" ]; then
                die "only one tag may be checked at a time"
            fi
            TAG="$1"
            shift
            ;;
    esac
done
if [ -z "$TAG" ]; then
    die "a release tag is required (for example, v1.2.3)"
fi

# --- 1. tag grammar ----------------------------------------------------------
#
# Leading zeroes are rejected for the same reason semver rejects them: v01.2.3
# and v1.2.3 would derive the same versionCode but look like different releases.
if [[ "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    MAJOR="${BASH_REMATCH[1]}"
    MINOR="${BASH_REMATCH[2]}"
    PATCH="${BASH_REMATCH[3]}"
    IS_PRERELEASE=0
elif [[ "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-[0-9A-Za-z][0-9A-Za-z.-]*$ ]]; then
    MAJOR="${BASH_REMATCH[1]}"
    MINOR="${BASH_REMATCH[2]}"
    PATCH="${BASH_REMATCH[3]}"
    IS_PRERELEASE=1
else
    die "tag '$TAG' is not vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-<label>; the moving verified tag is not a release tag"
fi

for component in "$MAJOR" "$MINOR" "$PATCH"; do
    if [ "$component" != "0" ] && [[ "$component" == 0* ]]; then
        die "tag '$TAG' has a leading zero in a version component"
    fi
done
[ "$((10#$MAJOR))" -le 200 ] || die "tag '$TAG' has a major version above 200"
[ "$((10#$MINOR))" -le 999 ] || die "tag '$TAG' has a minor version above 999"
[ "$((10#$PATCH))" -le 999 ] || die "tag '$TAG' has a patch version above 999"

# versionCode = major*10,000,000 + minor*10,000 + patch*10 + channel.
# Stable is +2, pre-release is +1, so v1.2.3-rc1 is below v1.2.3 and above
# v1.2.2. The component bounds keep the result below Android's 2,100,000,000
# limit and leave the channel digit room to order the two channels.
version_code_for() { # version_code_for <major> <minor> <patch> <channel>
    printf '%s' "$((10#$1 * 10000000 + 10#$2 * 10000 + 10#$3 * 10 + $4))"
}

VERSION_NAME="${TAG#v}"
CHANNEL=2
[ "$IS_PRERELEASE" -eq 1 ] && CHANNEL=1
VERSION_CODE="$(version_code_for "$MAJOR" "$MINOR" "$PATCH" "$CHANNEL")"
[ "$VERSION_CODE" -ge 1 ] || die "tag '$TAG' derives a non-positive versionCode"
[ "$VERSION_CODE" -le 2100000000 ] || die "tag '$TAG' derives versionCode $VERSION_CODE, above Android's 2,100,000,000 limit"

# --- 2. tag on the remote default branch ------------------------------------
#
# Query the remote rather than trusting origin/main: the checkout may be
# shallow, and the branch may have advanced since the last fetch.
TAG_COMMIT="$(git rev-parse --verify --quiet "${TAG}^{commit}" 2>/dev/null || true)"
[ -n "$TAG_COMMIT" ] || die "tag '$TAG' does not resolve to a commit in this checkout"

MAIN_SHA="$(git ls-remote --heads "$REMOTE" "$DEFAULT_BRANCH" 2>/dev/null \
    | awk -v ref="refs/heads/$DEFAULT_BRANCH" '$2 == ref { print $1; exit }')"
[ -n "$MAIN_SHA" ] || die "cannot resolve $REMOTE/$DEFAULT_BRANCH; fetch the remote default branch"
git cat-file -e "${MAIN_SHA}^{commit}" 2>/dev/null \
    || die "$REMOTE/$DEFAULT_BRANCH commit $MAIN_SHA is not in this checkout; fetch full history"
git merge-base --is-ancestor "$TAG_COMMIT" "$MAIN_SHA" 2>/dev/null \
    || die "tag '$TAG' is not on $DEFAULT_BRANCH (commit $TAG_COMMIT is not an ancestor of $MAIN_SHA)"

# --- 3. coverage by the remote verified tag ---------------------------------
#
# The local `verified` ref is deliberately never read. It is a moving tag, and
# a stale local copy is exactly the false green this check exists to prevent.
VERIFIED_SHA="$(git ls-remote --tags "$REMOTE" "refs/tags/verified" 2>/dev/null \
    | awk '$2 == "refs/tags/verified" || $2 == "refs/tags/verified^{}" { print $1; exit }')"
[ -n "$VERIFIED_SHA" ] || die "remote tag 'verified' does not exist; cannot prove the release commit passed verify-provenance"

if ! git cat-file -e "${VERIFIED_SHA}^{commit}" 2>/dev/null; then
    # The tag object can be absent in a shallow/tagless checkout even though the
    # remote has it. Fetch that one ref instead of falling back to a local guess.
    git fetch --quiet --no-tags "$REMOTE" "refs/tags/verified" 2>/dev/null || true
fi
git cat-file -e "${VERIFIED_SHA}^{commit}" 2>/dev/null \
    || die "remote verified commit $VERIFIED_SHA is not in this checkout; fetch full history"
git merge-base --is-ancestor "$TAG_COMMIT" "$VERIFIED_SHA" 2>/dev/null \
    || die "tag '$TAG' is not covered by the remote verified tag ($TAG_COMMIT is not an ancestor of $VERIFIED_SHA)"

# --- 4. monotonic version against the highest stable tag on the remote ------
#
# Read tags, not published releases: a stable tag is a version floor even if its
# release is temporarily marked pre-release during a rollback, and a stable tag
# without a release must not let an equal version through.
REMOTE_TAGS="$(git ls-remote --tags "$REMOTE" 'refs/tags/v*' 2>/dev/null)" \
    || die "cannot list release tags on $REMOTE"

LAST_CODE=0
LAST_TAG=""
while IFS=$'\t' read -r _ ref; do
    [ -n "$ref" ] || continue
    released_tag="${ref#refs/tags/}"
    released_tag="${released_tag%'^{}'}"
    # The tag being checked is on the remote too. It is not its own floor; a
    # stable build of a new tag and a promotion both need to see the previous
    # stable release, not themselves.
    [ "$released_tag" = "$TAG" ] && continue
    [[ "$released_tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || continue
    rmajor="${BASH_REMATCH[1]}"
    rminor="${BASH_REMATCH[2]}"
    rpatch="${BASH_REMATCH[3]}"
    valid=1
    for component in "$rmajor" "$rminor" "$rpatch"; do
        if [ "$component" != "0" ] && [[ "$component" == 0* ]]; then
            valid=0
        fi
    done
    [ "$valid" -eq 1 ] || continue
    [ "$((10#$rmajor))" -le 200 ] || continue
    [ "$((10#$rminor))" -le 999 ] || continue
    [ "$((10#$rpatch))" -le 999 ] || continue
    rcode="$(version_code_for "$rmajor" "$rminor" "$rpatch" 2)"
    if [ "$rcode" -gt "$LAST_CODE" ]; then
        LAST_CODE="$rcode"
        LAST_TAG="$released_tag"
    fi
done <<< "$REMOTE_TAGS"

if [ "$VERSION_CODE" -le "$LAST_CODE" ]; then
    die "tag '$TAG' derives versionCode $VERSION_CODE, which is not greater than the highest stable tag ${LAST_TAG:-<none>} ($LAST_CODE)"
fi

# A stable release for this exact tag means the version is being reused. Build
# mode rejects it; verify-only promotion must be allowed to see its own release
# while that release is still a pre-release.
if [ "$VERIFY_ONLY" -eq 0 ]; then
    command -v gh >/dev/null 2>&1 \
        || die "gh is required to check for an existing stable release"
    [ -n "${GITHUB_REPOSITORY:-}" ] \
        || die "GITHUB_REPOSITORY is required to check for an existing stable release"
    EXISTING_STABLE="$(gh release list --repo "$GITHUB_REPOSITORY" --limit 1000 \
        --json tagName,isPrerelease \
        --jq ".[] | select(.tagName == \"$TAG\" and .isPrerelease == false) | .tagName" 2>/dev/null)" \
        || die "cannot list releases for $GITHUB_REPOSITORY"
    [ -z "$EXISTING_STABLE" ] \
        || die "tag '$TAG' already has a stable GitHub Release; stable versions cannot be reused"
fi

# --- 5. release key and certificate fingerprint ------------------------------
#
# Both files are always checked, including in --verify-only mode: a release
# whose public certificate and published fingerprint disagree is not verifiable
# by a consumer either.
[ -f "$CERT_FILE" ] || die "public release certificate is missing: $CERT_FILE"
[ -f "$FINGERPRINT_FILE" ] || die "release certificate fingerprint is missing: $FINGERPRINT_FILE"
command -v openssl >/dev/null 2>&1 || die "openssl is required to read the public certificate"

EXPECTED_FINGERPRINT="$(tr -d '[:space:]' < "$FINGERPRINT_FILE" | tr 'A-F' 'a-f')"
[[ "$EXPECTED_FINGERPRINT" =~ ^[0-9a-f]{64}$ ]] \
    || die "$FINGERPRINT_FILE is not a SHA-256 certificate fingerprint"
PEM_FINGERPRINT="$(openssl x509 -in "$CERT_FILE" -noout -fingerprint -sha256 2>/dev/null \
    | sed 's/.*=//' | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
[ -n "$PEM_FINGERPRINT" ] || die "cannot read the certificate fingerprint from $CERT_FILE"
[ "$PEM_FINGERPRINT" = "$EXPECTED_FINGERPRINT" ] \
    || die "$CERT_FILE does not match $FINGERPRINT_FILE"

if [ "$VERIFY_ONLY" -eq 0 ]; then
    MISSING=()
    for name in RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
        if [ -z "${!name:-}" ]; then
            MISSING+=("$name")
        fi
    done
    if [ "${#MISSING[@]}" -ne 0 ]; then
        die "release key secrets are incomplete; missing: ${MISSING[*]}"
    fi

    if [ -n "${PREFLIGHT_KEYSTORE_OUT:-}" ]; then
        KEYSTORE="$PREFLIGHT_KEYSTORE_OUT"
        mkdir -p "$(dirname "$KEYSTORE")" || die "cannot create the keystore directory"
        : > "$KEYSTORE" || die "cannot create the keystore at $KEYSTORE"
    else
        KEYSTORE="$(mktemp "${TMPDIR:-/tmp}/bepinex-release.XXXXXX")" \
            || die "cannot create a temporary keystore"
    fi
    chmod 600 "$KEYSTORE" 2>/dev/null || true

    if ! printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE" 2>/dev/null; then
        die "RELEASE_KEYSTORE_BASE64 is not valid base64"
    fi

    KEYTOOL_OUT="$(LC_ALL=C keytool -list -v -keystore "$KEYSTORE" \
        -storepass "$RELEASE_KEYSTORE_PASSWORD" -alias "$RELEASE_KEY_ALIAS" 2>&1)" \
        || die "cannot open the release keystore with the supplied password and alias"
    ACTUAL_FINGERPRINT="$(printf '%s\n' "$KEYTOOL_OUT" \
        | sed -n 's/.*SHA256:[[:space:]]*//p' | head -1 \
        | tr -d '[:space:]:' | tr 'A-F' 'a-f')"
    [ -n "$ACTUAL_FINGERPRINT" ] \
        || die "cannot read the certificate fingerprint from the release keystore"
    if [ "$ACTUAL_FINGERPRINT" != "$EXPECTED_FINGERPRINT" ]; then
        die "release keystore certificate fingerprint $ACTUAL_FINGERPRINT does not match the committed fingerprint $EXPECTED_FINGERPRINT"
    fi
fi

# --- output -----------------------------------------------------------------
emit() {
    printf '%s=%s\n' "$1" "$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT" \
            || die "cannot write to GITHUB_OUTPUT"
    fi
}

if [ "$VERIFY_ONLY" -eq 1 ]; then
    printf 'release_preflight: %s (%s) is a %s tag; tag/main/verified/version checks passed\n' \
        "$VERSION_NAME" "$VERSION_CODE" "$([ "$IS_PRERELEASE" -eq 1 ] && echo pre-release || echo stable)"
else
    # The build consumes the materialized keystore, so it must survive this
    # process. The caller removes it in an always() step.
    KEEP_KEYSTORE=1
    printf 'release_preflight: %s (%s) is a %s tag; tag/main/verified/version/key checks passed\n' \
        "$VERSION_NAME" "$VERSION_CODE" "$([ "$IS_PRERELEASE" -eq 1 ] && echo pre-release || echo stable)"
fi

emit version_name "$VERSION_NAME"
emit version_code "$VERSION_CODE"
emit is_prerelease "$IS_PRERELEASE"
if [ "$VERIFY_ONLY" -eq 0 ]; then
    emit keystore_path "$KEYSTORE"
fi
