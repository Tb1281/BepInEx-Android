#!/usr/bin/env bash
# ==============================================================================
# BepInEx Android - device boot assertions
#
# Some things are only observable on a device, and several of them used to fail
# there and nowhere else. This turns the boot sequence into assertions so a
# regression names itself instead of showing up as an abort with no cause.
#
# Each assertion below corresponds to a failure that actually happened:
#
#   PAL: dlopen failed: ".../dotnet/libSystem.Security.Cryptography.Native.Android.so"
#       -> the PAL has to be inside dotnet.zip, not only in the APK's lib dir
#   Fatal signal 11 (SIGSEGV) fault addr 0x0, backtrace in the PAL
#       -> gJvm was never set; JNI_OnLoad is a 4-byte trampoline into
#          AndroidCryptoNative_InitLibraryOnLoad, and CoreCLR's own dlopen does
#          not go through Android's loader, so fusion has to call it
#   Abort message: 'GetClassGRef: class net/dot/android/crypto/DotnetProxyTrustManager
#                  was not found'
#       -> the PAL's Java half was not on the :game classpath
#
# Usage: assert_device_boot.sh [package] [trace.log] [LogOutput.log]
#        (default package: com.oninou.revolution)
#
# Two logs, because the sequence spans two writers:
#   trace.log     libfusion's own trace - native bootstrap, up to AndroidEntrypoint.Start
#   LogOutput.log BepInEx's managed log - interop generation and the chainloader
# Reading only the first makes assertions 6 and 7 fail on a healthy run.
#
# Pull both:
#   adb shell cat /sdcard/Android/data/com.bepinex.android/files/bepinex_trace.log > trace.log
#   adb shell cat /sdcard/BepInEx_Android/$PKG/BepInEx/LogOutput.log        > LogOutput.log
# ==============================================================================

set -uo pipefail

PKG="${1:-com.oninou.revolution}"
LOG="${2:-trace.log}"
MLOG="${3:-LogOutput.log}"

FAILURES=0
fail() { echo "  [FAIL]  $*"; FAILURES=$((FAILURES + 1)); }
ok()   { echo "  [OK]    $*"; }

echo "============================================================"
echo " BepInEx Android - device boot assertions                 "
echo "============================================================"
echo "native trace : ${LOG}"
echo "managed log  : ${MLOG}"

if [ ! -f "$LOG" ]; then
    echo "  [FAIL]  no native trace log at ${LOG}"
    exit 1
fi

# The managed log is optional: a failure before the entry point runs legitimately
# produces none, and the native assertions still have something to say.
if [ -f "$MLOG" ]; then
    HAVE_MLOG=1
else
    HAVE_MLOG=0
fi

has() { grep -qF "$1" "$LOG"; }
hasm() { [ "$HAVE_MLOG" = 1 ] && grep -qF "$1" "$MLOG"; }

echo ""
echo "[1/7] libmain resolves and loads libfusion..."
if has "load: COMPLETE"; then
    ok "load: COMPLETE"
else
    fail "the bootstrap never completed (no 'load: COMPLETE')"
    echo "         Pine did not redirect findLibrary(\"main\"), or fusion failed to stage"
fi

echo ""
echo "[2/7] il2cpp_init hook fired..."
if has "il2cpp_init HOOK FIRED!"; then
    ok "il2cpp_init HOOK FIRED!"
else
    fail "the il2cpp_init hook never fired — CoreCLR cannot start without it"
fi

echo ""
echo "[3/7] CoreCLR started..."
if has "CoreCLR started"; then
    ok "CoreCLR started"
else
    fail "CoreCLR did not start (see 'coreclr_initialize failed' in the log)"
fi

echo ""
echo "[4/7] The crypto PAL was handed a JavaVM..."
# This is the assertion for the gJvm SIGSEGV. Without it the PAL dereferences a
# null JavaVM on the first AndroidCryptoNative_* call and takes the process with
# a SIGSEGV whose backtrace is entirely inside the PAL.
if has "PAL: JNI_OnLoad rc="; then
    rc="$(grep -o 'PAL: JNI_OnLoad rc=[0-9-]*' "$LOG" | tail -1 | sed 's/.*rc=//')"
    if [ "$rc" = "65542" ]; then
        ok "PAL: JNI_OnLoad rc=${rc} (JNI_VERSION_1_6)"
    else
        fail "PAL: JNI_OnLoad rc=${rc}, expected 65542 (JNI_VERSION_1_6)"
    fi
else
    fail "the PAL was never given a JavaVM"
    echo "         libfusion must dlopen the PAL and call JNI_OnLoad before"
    echo "         coreclr_initialize; otherwise gJvm stays NULL and the first"
    echo "         crypto call faults at address 0x0"
fi

echo ""
echo "[5/7] The PAL found its Java classes..."
# The PAL aborts (not throws) when these are missing.
if grep -q "GetClassGRef.*was not found" "$LOG"; then
    fail "the PAL could not resolve a net.dot.android.crypto class:"
    grep "GetClassGRef.*was not found" "$LOG" | sed 's/^/         /'
    echo "         run launcher/scripts/verify_crypto_java.sh"
else
    ok "no GetClassGRef failure"
fi

echo ""
echo "[6/7] Interop assemblies were generated..."
if hasm "Il2CppInteropGen] Done!"; then
    ok "Il2CppInteropGen Done! (managed log)"
elif [ "$HAVE_MLOG" = 0 ]; then
    fail "no managed log to check - the entry point never ran far enough to write one"
else
    fail "interop generation did not finish - the pack is likely the wrong RID"
    echo "         run scripts/verify_dotnet_pack.sh on the shipped dotnet.zip"
fi

echo ""
echo "[7/7] Chainloader came up and the export stub resolved..."
if hasm "Chainloader initialized"; then
    ok "Chainloader initialized (managed log)"
else
    fail "the chainloader never initialised"
fi
if hasm "Resolved il2cpp_runtime_invoke export stub"; then
    ok "il2cpp_runtime_invoke export stub resolved"
else
    fail "the export stub was not resolved; the chainloader will never see a call"
fi

# Reported, not failed: this is the open item in docs/progress.md step 9.
echo ""
echo "[note] Managed stack overflow / SO Tracer, if present below:"
if grep -q "Stack overflow" "$LOG" || grep -q "SO Tracer" "$LOG"; then
    grep -nE "Stack overflow|SO Tracer" "$LOG" | sed 's/^/         /'
    echo "         open item — see docs/progress.md step 9"
else
    echo "         none in the fusion trace (check 'adb logcat -b crash -d')"
fi

echo ""
echo "============================================================"
if [ "${FAILURES}" -ne 0 ]; then
    echo " RESULT: FAILED (${FAILURES} problem(s))"
    echo "============================================================"
    exit 1
fi
echo " RESULT: PASSED - the bootstrap sequence is intact."
echo "============================================================"