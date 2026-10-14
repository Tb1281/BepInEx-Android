#!/usr/bin/env bash
# Fixture for scripts/verify_release_assets.sh.
#
# WHY THIS EXISTS
#
# The script is the only consumer-side check on the artifact users install,
# so its failure directions are the whole value: a release page showing a
# stale APK with a fresh-looking bundle must FAIL here, not pass. `gh` is
# stubbed (same precedent as test_attestation_gate.sh), so each case feeds
# the stub canned release assets and a verdict:
#
#   - complete release (APK + manifest + bundle, all agreeing) PASSES.
#   - tampered APK (checksum mismatch) FAILS. A gate that passed because the
#     attestation was fine while the bytes differed is decoration.
#   - bundle that does not vouch for the APK FAILS.
#   - missing bundle asset FAILS rather than falling back to "unverified".
#   - missing gh, or gh without `attestation`, FAILS rather than skipping.
#   - unknown tag (nothing to download) FAILS.
set -uo pipefail

FIX="$(mktemp -d "${TMPDIR:-/tmp}/release-verify-fix.XXXXXX")"
trap 'rm -rf "$FIX"' EXIT
export FIX
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$REPO/scripts/verify_release_assets.sh"

# The stub reads $FIX/mode to decide behavior per case:
#   ok | tampered | badbundle | nobundle | notrelease
mkdir -p "$FIX/bin"
cat > "$FIX/bin/gh" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "--version" ]; then echo "gh version 2.80.0"; exit 0; fi
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ] && [ "${3:-}" = "--help" ]; then
    if [ "${NO_ATTESTATION_CMD:-0}" = 1 ]; then exit 1; fi
    exit 0
fi
if [ "${1:-}" = "release" ] && [ "${2:-}" = "download" ]; then
    mode="$(cat "$FIX/mode" 2>/dev/null || echo ok)"
    if [ "$mode" = "notrelease" ]; then
        echo "release not found" >&2; exit 1
    fi
    dest=""
    prev=""
    for a in "$@"; do
        [ "$prev" = "--dir" ] && dest="$a"
        prev="$a"
    done
    printf 'fake-apk-bytes-%s' "$mode" > "$dest/app-release-aligned.apk"
    if [ "$mode" = "tampered" ]; then
        # Manifest records DIFFERENT bytes than the "downloaded" APK.
        printf 'other-bytes' | sha256sum | awk '{print $1"  app-release-aligned.apk"}' > "$dest/app-release-aligned.apk.sha256"
    else
        sha256sum "$dest/app-release-aligned.apk" | sed 's| .*|  app-release-aligned.apk|' > "$dest/app-release-aligned.apk.sha256"
    fi
    [ "$mode" = "nobundle" ] || echo '{"bundle":"stub"}' > "$dest/sync-apk.attestation.json"
    exit 0
fi
if [ "${1:-}" = "attestation" ] && [ "${2:-}" = "verify" ]; then
    [ "$(cat "$FIX/mode" 2>/dev/null || echo ok)" = "badbundle" ] && exit 1
    exit 0
fi
exit 1
STUB
chmod +x "$FIX/bin/gh"

fails=0
run_case() { # run_case <label> <want-rc> <mode> [env...]
    local label="$1" want="$2" mode="$3"; shift 3
    echo "$mode" > "$FIX/mode"
    local out rc
    out="$(env PATH="$FIX/bin:$PATH" GITHUB_REPOSITORY="o/r" "$SCRIPT" v9.9 "$FIX/out-$mode" 2>&1)"; rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL  $label: expected exit $want, got $rc"
        printf '%s\n' "$out" | sed 's/^/        /'
        fails=$((fails + 1))
    else
        echo "ok    $label (exit $rc)"
    fi
    rm -rf "$FIX/out-$mode"
}

echo "== verify_release_assets.sh =="
run_case "complete release passes" 0 ok
run_case "tampered APK fails" 1 tampered
run_case "unvouched bundle fails" 1 badbundle
run_case "missing bundle fails" 1 nobundle
run_case "unknown tag fails" 1 notrelease

# Missing gh fails rather than skipping.
if PATH="/nonexistent" GITHUB_REPOSITORY="o/r" "$SCRIPT" v9.9 "$FIX/out-nogh" >/dev/null 2>&1; then
    echo "FAIL  missing gh passed"; fails=$((fails + 1))
else
    echo "ok    missing gh fails"
fi
rm -rf "$FIX/out-nogh"

# gh without the attestation subcommand fails rather than misreporting.
mkdir -p "$FIX/bin-nocmd"
printf '#!/bin/sh\nif [ "$1" = "--version" ]; then echo "gh version 2.0.0"; fi\nexit 1\n' > "$FIX/bin-nocmd/gh"
chmod +x "$FIX/bin-nocmd/gh"
if PATH="$FIX/bin-nocmd:/usr/bin:/bin" GITHUB_REPOSITORY="o/r" "$SCRIPT" v9.9 "$FIX/out-nocmd" >/dev/null 2>&1; then
    echo "FAIL  gh-without-attestation passed"; fails=$((fails + 1))
else
    echo "ok    gh-without-attestation fails"
fi
rm -rf "$FIX/out-nocmd"

echo ""
if [ "$fails" -eq 0 ]; then echo "ALL FIXTURE CASES PASSED"; else echo "$fails CASE(S) FAILED"; fi
exit "$fails"
