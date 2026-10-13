/*
 * net.dot.android.crypto.DotnetX509KeyManager
 *
 * Re-implementation of the class that ships inside
 * Microsoft.NETCore.App.Runtime.android-arm64 as
 * libSystem.Security.Cryptography.Native.Android.jar. The PAL resolves it by
 * name; see DotnetProxyTrustManager for why the sources live here.
 *
 * Contract required by the PAL:
 *   <init>  (Ljava/security/KeyStore$PrivateKeyEntry;)V
 *
 * Serves exactly one client certificate, under a fixed alias. The alias is a
 * contract too: the native side looks the alias up to decide whether a peer was
 * configured for client-certificate authentication, so the string must not
 * change.
 */

package net.dot.android.crypto;

import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509KeyManager;

public final class DotnetX509KeyManager implements X509KeyManager {

    private static final String CLIENT_CERTIFICATE_ALIAS = "DOTNET_SSLStream_ClientCertificateContext";

    private final PrivateKey privateKey;
    private final X509Certificate[] certificateChain;

    public DotnetX509KeyManager(KeyStore.PrivateKeyEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("PrivateKeyEntry must not be null");
        }

        this.privateKey = entry.getPrivateKey();

        Certificate[] chain = entry.getCertificateChain();
        List<X509Certificate> x509Chain = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (certificate instanceof X509Certificate) {
                x509Chain.add((X509Certificate) certificate);
            }
        }

        if (x509Chain.isEmpty()) {
            throw new IllegalArgumentException("No valid X509 certificates found in the chain");
        }

        this.certificateChain = x509Chain.toArray(new X509Certificate[0]);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[] { CLIENT_CERTIFICATE_ALIAS };
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return CLIENT_CERTIFICATE_ALIAS;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return certificateChain;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return privateKey;
    }
}