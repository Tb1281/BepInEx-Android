#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - crypto Java contract check
#
# The CoreCLR on Android crypto PAL (libSystem.Security.Cryptography.Native.Android.so)
# implements every algorithm by calling into java.security / javax.crypto through JNI.
# Three classes make up its Java side, and it resolves them by name at startup:
#
#   net/dot/android/crypto/DotnetProxyTrustManager
#   net/dot/android/crypto/DotnetX509KeyManager
#   net/dot/android/crypto/PalPbkdf2
#
# If a class or a member is missing the PAL does not throw -- it aborts the
# process:
#
#   F DOTNET : GetClassGRef: class net/dot/android/crypto/DotnetProxyTrustManager was not found
#   F DEBUG  : Abort message: 'GetClassGRef: ... was not found'
#   Fatal signal 6 (SIGABRT)
#
# Observed on device with a mismatched constructor, .NET 10.0.12 pack:
#
#   Abort message: '.../pal_jni.c:619 (GetMethod): method <init> (J)V was not found'
#   signal 6 (SIGABRT) ... libfusion.so (dotnet_start_runtime())
#
# The expected descriptors are NOT written here. They are read from
# provenance/pal-java-contract.txt, which scripts/gen_pal_java_contract.sh
# generates from the jar inside the pinned runtime pack. That is deliberate: the
# contract moves between .NET releases, and this check previously hardcoded the
# .NET 11 form, so it reported PASSED on a build that aborted on the device. The
# signatures are now a property of the pin, exactly like sha256sums.txt.
#
#   .NET 11.0.0-rc.1                    .NET 10.0.12
#   <init>(J X509TrustManager String)V  <init>(J)V
#   verifyRemoteCertificate(J String)Z  verifyRemoteCertificate(J)Z
#
# verify_crypto_pal.sh checks the native half. This checks the managed half.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${ROOT_DIR}/.." && pwd)"
SRC_DIR="${ROOT_DIR}/app/src/main/java"
PKG_DIR="${SRC_DIR}/net/dot/android/crypto"
CONTRACT="${REPO_DIR}/provenance/pal-java-contract.txt"

FAILURES=0
fail() { echo "  [FAIL] $*"; FAILURES=$((FAILURES + 1)); }
ok()   { echo "  [OK]   $*"; }

CLASSES="DotnetProxyTrustManager DotnetX509KeyManager PalPbkdf2"

echo "============================================================"
echo " BepInEx Android - crypto Java contract                   "
echo "============================================================"
echo "sources : ${PKG_DIR}"
echo "contract: ${CONTRACT}"

# 0. The contract itself must exist. A gate that skips when it is missing is the
#    failure this script already had once, so absence is an error, not a skip.
echo ""
echo "[0/5] Pinned PAL contract available..."
if [ ! -f "$CONTRACT" ]; then
    echo "  [FAIL] ${CONTRACT} not found."
    echo "         Generate it with:"
    echo "           ./scripts/fetch_dotnet_runtime.sh <out-dir>"
    echo "         Do not replace it with a literal: a hardcoded signature is what"
    echo "         let a device-aborting build pass this check."
    echo ""
    echo " RESULT: FAILED (contract unavailable)"
    echo "============================================================"
    exit 1
fi
ok "$(grep -vc '^#' "$CONTRACT" || true) member descriptors from the pinned pack"

# Render a Java parameter list to a JNI descriptor. Unknown types are an error
# rather than a guess -- a wrong guess here is a false PASS.
render_desc() {
    local params="$1" out="" tok
    for tok in $params; do
        case "$tok" in
            long) out+="J" ;; int) out+="I" ;; boolean) out+="Z" ;; void) out+="V" ;;
            'byte[]') out+="[B" ;;
            X509Certificate) out+="Ljava/security/cert/X509Certificate;" ;;
            X509Certificate[]) out+="[Ljava/security/cert/X509Certificate;" ;;
            'X509Certificate[]') out+="[Ljava/security/cert/X509Certificate;" ;;
            X509TrustManager) out+="Ljavax/net/ssl/X509TrustManager;" ;;
            X509KeyManager) out+="Ljavax/net/ssl/X509KeyManager;" ;;
            KeyStore.PrivateKeyEntry) out+="Ljava/security/KeyStore\$PrivateKeyEntry;" ;;
            'KeyStore.PrivateKeyEntry;') out+="Ljava/security/KeyStore\$PrivateKeyEntry;" ;;
            KeyStore.PrivateKeyEntry\;) out+="Ljava/security/KeyStore\$PrivateKeyEntry;" ;;
            ByteBuffer) out+="Ljava/nio/ByteBuffer;" ;;
            String) out+="Ljava/lang/String;" ;;
            Principal) out+="Ljava/security/Principal;" ;;
            'Principal[]') out+="[Ljava/security/Principal;" ;;
            Socket) out+="Ljava/net/Socket;" ;;
            *) echo "UNKNOWN_TYPE:${tok}" >&2; return 1 ;;
        esac
    done
    printf '(%s)' "$out"
}

render_return() {
    case "$1" in
        void) printf 'V' ;; boolean) printf 'Z' ;; int) printf 'I' ;;
        long) printf 'J' ;; String) printf 'Ljava/lang/String;' ;;
        X509Certificate) printf 'Ljava/security/cert/X509Certificate;' ;;
        'X509Certificate[]') printf '[Ljava/security/cert/X509Certificate;' ;;
        PrivateKey) printf 'Ljava/security/PrivateKey;' ;;
        *) echo "UNKNOWN_RET:$1" >&2; return 1 ;;
    esac
}

# Expected descriptor for <class>\t<member> out of the contract file.
expect() {
    awk -F'\t' -v c="$1" -v m="$2" '$1 == c && $2 == m { print $3; exit }' "$CONTRACT"
}

# Source text with comments removed, flattened to one line.
#
# Stripping comments is not cosmetic. These files carry a header comment that
# documents the contract, and it contains the very tokens being looked for --
# "`static native` declaration", "verifyRemoteCertificate(J String)Z" -- so a grep
# over the raw text matches the documentation of the old contract instead of the
# declaration of the current one. That produced a false failure here, and the same
# shape would produce a false PASS in the other direction.
code_of() {
    perl -0777 -pe 's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' "$1" | tr '\n' ' '
}

# Parameter types of a declaration, with identifiers and generics dropped.
# The trailing `|| true` matters: a no-argument constructor makes grep match
# nothing, which under `set -e` would abort the whole gate instead of yielding
# "()" and then "()V".
params_of() {
    printf '%s' "$1" | sed -E 's/^.*\(([^)]*)\).*$/\1/' | tr ',' '\n' |
        sed -E 's/^[[:space:]]*final[[:space:]]+//' |
        sed -E 's/^[[:space:]]*//; s/[[:space:]].*$//' | grep -v '^$' || true
}

# 1. Classes exist in the package the PAL resolves, and declare that package.
echo ""
echo "[1/5] Classes present in the package the PAL resolves..."
for cls in $CLASSES; do
    f="${PKG_DIR}/${cls}.java"
    if [ ! -f "$f" ]; then
        fail "${cls}.java missing — the PAL aborts the process without it"
        continue
    fi
    ok "${cls}.java"
    if grep -qE '^package net\.dot\.android\.crypto;$' "$f"; then
        ok "${cls} declares package net.dot.android.crypto"
    else
        fail "${cls} does not declare 'package net.dot.android.crypto;' (FindClass would miss it)"
    fi
done

# 2. Constructors. This is the lookup that aborted on device.
echo ""
echo "[2/5] Constructor descriptors vs the pinned pack..."
for cls in $CLASSES; do
    f="${PKG_DIR}/${cls}.java"
    [ -f "$f" ] || continue
    want="$(expect "net/dot/android/crypto/${cls}" '<init>')"
    if [ -z "$want" ]; then
        fail "${cls}: the pinned pack declares no <init>; cannot verify"
        continue
    fi
    got=""
    # A constructor is a declaration naming the class itself, with no return type.
    # The `|| true` is required: under `set -e` with `pipefail`, grep finding nothing
    # would abort the gate instead of yielding an empty match.
    decl="$(code_of "$f" | grep -oE "(public|private|protected)?[[:space:]]*${cls}[[:space:]]*\([^)]*\)[[:space:]]*(throws[^{;]*)?[{;]" | head -1 || true)"
    if [ -n "$decl" ]; then
        types="$(params_of "$decl" | tr '\n' ' ')"
        if got="$(render_desc "$types" 2>/dev/null)" && [ -n "$got" ]; then
            got="${got}V"
        else
            got="?"
        fi
    elif [ "$want" = "()V" ]; then
        # No explicit constructor and the pack wants the no-arg one: that is exactly
        # what javac synthesises, so the descriptor matches without a declaration.
        got="()V"
        ok "${cls} declares no constructor; javac emits the implicit <init>()V the pack wants"
    else
        got="none"
    fi
    if [ "$got" = "$want" ]; then
        ok "${cls}<init> ${got}"
    else
        fail "${cls}<init> is ${got}, the pinned ${DOTNET_RUNTIME_VERSION:-pack} PAL wants ${want}"
    fi
done

# 3. Static members the PAL resolves by name: the RegisterNatives target and
#    PalPbkdf2.pbkdf2OneShot. Instance and private members are implementation
#    detail and are not looked up, so they are not asserted.
echo ""
echo "[3/5] Static member descriptors vs the pinned pack..."
check_static() {
    local cls="$1" name="$2" re="$3"
    local f="${PKG_DIR}/${cls}.java"
    [ -f "$f" ] || return 0
    local want got decl types
    want="$(expect "net/dot/android/crypto/${cls}" "$name")"
    if [ -z "$want" ]; then
        fail "${cls}.${name}: not in the pinned contract — remove it or the pack changed"
        return 0
    fi
    decl="$(code_of "$f" | grep -oE "static[^;{]*\b${name}[[:space:]]*\([^)]*\)" | head -1 || true)"
    if [ -z "$decl" ]; then
        fail "${cls}.${name} is not declared static; RegisterNatives/GetStaticMethodID needs it"
        return 0
    fi
    types="$(params_of "$decl" | tr '\n' ' ')"
    got="$(render_desc "$types" 2>/dev/null)" || got="?"
    got="${got}$(render_return "$re" 2>/dev/null || echo '?')"
    if [ "$got" = "$want" ]; then
        ok "${cls}.${name} ${got}"
    else
        fail "${cls}.${name} is ${got}, the pinned PAL wants ${want}"
    fi
}
check_static DotnetProxyTrustManager verifyRemoteCertificate boolean
check_static PalPbkdf2 pbkdf2OneShot int

# RegisterNatives binds by name and descriptor, so 'static native' specifically is
# required -- a non-native static would compile and then fail to bind.
TRUST_F="${PKG_DIR}/DotnetProxyTrustManager.java"
if [ -f "$TRUST_F" ]; then
    if grep -qE '^[[:space:]]*static[[:space:]]+native[[:space:]]+boolean[[:space:]]+verifyRemoteCertificate[[:space:]]*\(' "$TRUST_F"; then
        ok 'verifyRemoteCertificate is declared "static native boolean"'
    else
        fail 'verifyRemoteCertificate must be "static native boolean" for RegisterNatives'
    fi
    if grep -qE 'implements X509TrustManager' "$TRUST_F"; then
        ok 'DotnetProxyTrustManager implements X509TrustManager'
    else
        fail 'DotnetProxyTrustManager must implement X509TrustManager (SSLEngine casts it)'
    fi
fi

KEY_F="${PKG_DIR}/DotnetX509KeyManager.java"
if [ -f "$KEY_F" ]; then
    grep -qE 'implements X509KeyManager' "$KEY_F" \
        && ok 'DotnetX509KeyManager implements X509KeyManager' \
        || fail 'DotnetX509KeyManager must implement X509KeyManager'
    # The alias is a contract too: the native side looks it up to decide whether a
    # peer was configured for client-certificate authentication.
    if grep -q 'DOTNET_SSLStream_ClientCertificateContext' "$KEY_F"; then
        ok 'client certificate alias preserved'
    else
        fail 'client certificate alias changed (must be DOTNET_SSLStream_ClientCertificateContext)'
    fi
fi

PBKDF_F="${PKG_DIR}/PalPbkdf2.java"
if [ -f "$PBKDF_F" ]; then
    # Return values the PAL interprets: 1 = success, -1 = unsupported algorithm.
    if grep -qE 'ERROR_UNSUPPORTED_ALGORITHM[[:space:]]*=[[:space:]]*-1;' "$PBKDF_F" \
        && grep -qE 'SUCCESS[[:space:]]*=[[:space:]]*1;' "$PBKDF_F"; then
        ok 'return codes: SUCCESS=1, ERROR_UNSUPPORTED_ALGORITHM=-1'
    else
        fail 'PalPbkdf2 return codes must stay SUCCESS=1 / ERROR_UNSUPPORTED_ALGORITHM=-1'
    fi
fi

# 4. Every member the contract lists as static must exist in the sources. A static
#    the pack expects but the source lacks is a runtime abort waiting to happen.
echo ""
echo "[4/5] Every static member the pack names is declared..."
missing_static=0
for pair in "DotnetProxyTrustManager verifyRemoteCertificate" \
            "PalPbkdf2 pbkdf2OneShot"; do
    set -- $pair
    cls="$1"; member="$2"
    f="${PKG_DIR}/${cls}.java"
    [ -f "$f" ] || continue
    if ! code_of "$f" | grep -qE "static[^;{]*\b${member}[[:space:]]*\("; then
        fail "${cls}.${member} is required by the pinned pack but absent from the source"
        missing_static=$((missing_static + 1))
    fi
done
[ "${missing_static}" -eq 0 ] && ok 'every static member the pinned pack names is declared'

# 5. If a built APK is around, confirm the descriptors actually landed in a dex.
#
# This checks the RELEASE apk, not the debug one. The debug apk is not what ships,
# and it is not even minified with the project's own rules - proguardFiles is
# declared only on the release build type, so turning on isMinifyEnabled for debug
# runs R8 with none of proguard-rules.pro. That is not hypothetical: doing it
# strips DotnetX509KeyManager's constructor and the only thing that noticed was
# this check, pointed at the wrong artifact.
#
# Release is preferred, then debug, so a build that only has one of the two still
# gets inspected. Release first because that is the one that ships.
echo ""
echo "[5/5] Built APK..."
APK=""
for candidate in \
    "${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk" \
    "${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
do
    if [ -f "$candidate" ]; then
        APK="$candidate"
        break
    fi
done
if [ -z "$APK" ]; then
    echo "  [SKIP] no release or debug APK under ${ROOT_DIR}/app/build/outputs/apk"
elif [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    echo "  [SKIP] neither ANDROID_HOME nor ANDROID_SDK_ROOT is set"
else
    DEXDUMP=""
    for cand in "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/*/dexdump; do
        [ -x "$cand" ] && DEXDUMP="$cand"
    done
    if [ -z "$DEXDUMP" ]; then
        echo "  [SKIP] dexdump not found under build-tools"
    else
        WORK="$(mktemp -d)"
        trap 'rm -rf "$WORK"' EXIT
        unzip -q -o "$APK" 'classes*.dex' -d "$WORK"
        # dexdump only reports on the first file it is given, so every dex is
        # scanned into one listing. A multi-class project spreads them.
        : > "$WORK/all.txt"
        for dex in "$WORK"/classes*.dex; do
            [ -f "$dex" ] || continue
            "$DEXDUMP" -f "$dex" 2>/dev/null >> "$WORK/all.txt" || true
        done
        # The dex is the authority here: this is what the PAL will look up.
        # index() rather than a regex match, because a descriptor is not a pattern --
        # "(J)V" as a regex is a capture group matching "JV" and silently finds
        # nothing, which is a false FAIL on a correct build.
        for cls in $CLASSES; do
            want="$(expect "net/dot/android/crypto/${cls}" '<init>')"
            [ -n "$want" ] || continue
            # The descriptor and the <init> name have to belong to the same class.
            # A single inc= flag carried across class blocks would let one class's
            # constructor satisfy another's check, so inc is cleared when the next
            # class header is seen.
            if LC_ALL=C awk -v c="'Lnet/dot/android/crypto/${cls};'" \
                    -v ninit="name          : '<init>'" \
                    -v ntype="type          : '${want}'" \
                    -v ncls="Class descriptor  : " '
                index($0, ncls) > 0 { inc = (index($0, c) > 0); n = 0; next }
                inc && index($0, ninit) > 0 { n = 1; next }
                n && index($0, ntype) > 0 { found = 1; n = 0; inc = 0 }
                END { exit(found ? 0 : 1) }' "$WORK/all.txt"
            then
                ok "${cls} in ${APK##*/} declares <init>${want}"
            else
                fail "${cls} in ${APK##*/} does not declare <init>${want} (the PAL would abort)"
            fi
        done
    fi
fi

echo ""
echo "============================================================"
if [ "${FAILURES}" -ne 0 ]; then
    echo " RESULT: FAILED (${FAILURES} problem(s))"
    echo "============================================================"
    exit 1
fi
echo " RESULT: PASSED - the PAL's Java contract matches the pinned pack."
echo "============================================================"
