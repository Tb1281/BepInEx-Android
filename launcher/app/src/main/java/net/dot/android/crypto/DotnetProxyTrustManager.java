/*
 * net.dot.android.crypto.DotnetProxyTrustManager
 *
 * Re-implementation of the class that ships inside
 * Microsoft.NETCore.App.Runtime.android-arm64 as
 * libSystem.Security.Cryptography.Native.Android.jar. The .NET crypto PAL
 * resolves this class by name and aborts the process if it is missing:
 *
 *   F DOTNET : GetClassGRef: class net/dot/android/crypto/DotnetProxyTrustManager was not found
 *   F DEBUG  : Abort message: 'GetClassGRef: ... was not found'
 *
 * So the package, the class name, the constructor descriptor and the native
 * method below are a contract with that PAL and must not be renamed:
 *
 *   <init>                            (J)V
 *   verifyRemoteCertificate           (J)Z
 *
 * verifyRemoteCertificate is registered from native code via RegisterNatives,
 * which requires the `static native` declaration to match exactly.
 *
 * Behaviour is a faithful transcription of the shipped class, decompiled with
 * javap -c from the pinned package. Why it lives here rather than being shipped
 * as the jar: the injected game runs in the launcher's own `:game` process, so
 * the launcher's classes are already on its classpath and the PAL's FindClass
 * resolves against them. See docs/progress.md, step 8.
 *
 * ---------------------------------------------------------------------------
 * THIS CONTRACT TRACKS THE PINNED RUNTIME PACK. It is not stable across .NET
 * releases, and getting it wrong aborts the process rather than failing softly.
 * Observed on device with the .NET 10.0.12 pack while this file still carried
 * the .NET 11 contract:
 *
 *   F DOTNET : *** *** ***
 *   Abort message: '.../pal_jni.c:619 (GetMethod): method <init> (J)V was not found'
 *   signal 6 (SIGABRT) ... dotnet_start_runtime()
 *   backtrace: libc.so(abort+160)
 *              dotnet/libSystem.Security.Cryptography.Native.Android.so
 *              dotnet/libSystem.Security.Cryptography.Native.Android.so
 *              lib/arm64/libfusion.so (dotnet_start_runtime())
 *
 * .NET 11.0.0-rc.1 versus .NET 10.0.12, measured with javap on both jars:
 *
 *   .NET 11.0.0-rc.1        .NET 10.0.12
 *   <init>(J X509TrustManager String)   <init>(J)
 *   verifyRemoteCertificate(J String)Z  verifyRemoteCertificate(J)Z
 *   + platformTrustManager              (없음)
 *   + platformTrustManagerExtensions   (없음)
 *   + targetHost                        (없음)
 *   + getServerTrustValidationError     (없음)
 *   + getClientTrustValidationError     (없음)
 *
 * .NET 10 delegates the whole chain decision to native code and ignores both the
 * chain and the auth type; .NET 11 evaluates the platform trust manager in Java
 * and passes the failure text across. So do not "fix" the unused parameters
 * away, and do not carry the .NET 11 fields over.
 *
 * DotnetX509KeyManager and PalPbkdf2 are byte-identical between the two packs.
 * Only this class changed, which is why only this file is version-specific.
 * ---------------------------------------------------------------------------
 */

package net.dot.android.crypto;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

public final class DotnetProxyTrustManager implements X509TrustManager {

    private final long sslStreamProxyHandle;

    public DotnetProxyTrustManager(long sslStreamProxyHandle) {
        this.sslStreamProxyHandle = sslStreamProxyHandle;
    }

    /**
     * chain and authType are deliberately unused. The shipped .NET 10 class
     * ignores both and asks only about the native handle; the decision is made
     * on the native side, which owns the SSLStream.
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (!verifyRemoteCertificate(sslStreamProxyHandle)) {
            throw new CertificateException();
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (!verifyRemoteCertificate(sslStreamProxyHandle)) {
            throw new CertificateException();
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /**
     * Implemented by libSystem.Security.Cryptography.Native.Android.so and bound
     * through RegisterNatives during its JNI_OnLoad.
     *
     * @param sslStreamProxyHandle handle of the native SSLStream being validated
     * @return true when the native side considers the chain acceptable
     */
    static native boolean verifyRemoteCertificate(long sslStreamProxyHandle);
}
