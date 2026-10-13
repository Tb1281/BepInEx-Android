/*
 * net.dot.android.crypto.PalPbkdf2
 *
 * Re-implementation of the class that ships inside
 * Microsoft.NETCore.App.Runtime.android-arm64 as
 * libSystem.Security.Cryptography.Native.Android.jar. The PAL resolves it by
 * name; see DotnetProxyTrustManager for why the sources live here.
 *
 * Contract required by the PAL:
 *   static int pbkdf2OneShot(Ljava/lang/String;[BLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;)I
 *
 * Android has no PBKDF2 in MessageDigest and the PAL does not implement it in
 * C, so it is done here over javax.crypto.Mac. That is why this class exists
 * rather than a native routine: the algorithm is not available as a
 * MessageDigest on the platform.
 *
 * Faithful transcription of the shipped class, decompiled with javap -c. The
 * loop is standard PBKDF2 (RFC 8018 §5.2) written out over Mac, including the
 * block counter and the U XOR accumulation, because javax.crypto does not offer
 * PBEWithHmacSHA* with a caller-supplied destination buffer.
 */

package net.dot.android.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

public final class PalPbkdf2 {

    private static final int ERROR_UNSUPPORTED_ALGORITHM = -1;
    private static final int SUCCESS = 1;

    /**
     * @param algorithmName   digest name, e.g. "SHA256"; the Mac is "Hmac" + this
     * @param password        key material; a zero-length array is replaced by {0}
     * @param salt            optional, read and reset per block so the caller keeps ownership
     * @param iterationCount  number of PRF iterations
     * @param destination     filled with the derived key; drives the loop length
     * @return 1 on success, -1 when the algorithm is unavailable
     */
    public static int pbkdf2OneShot(String algorithmName,
                                    byte[] password,
                                    ByteBuffer salt,
                                    int iterationCount,
                                    ByteBuffer destination)
            throws ShortBufferException, InvalidKeyException, IllegalArgumentException {
        if (algorithmName == null || password == null || destination == null) {
            throw new IllegalArgumentException(
                    "algorithmName, password, and destination must not be null.");
        }

        String macAlgorithm = "Hmac" + algorithmName;

        Mac mac;
        try {
            mac = Mac.getInstance(macAlgorithm);
        } catch (NoSuchAlgorithmException e) {
            return ERROR_UNSUPPORTED_ALGORITHM;
        }

        // A zero-length key is rejected by SecretKeySpec; PBKDF2 specifies a
        // single zero octet for an empty password.
        if (password.length == 0) {
            password = new byte[] { 0 };
        }

        if (salt != null) {
            salt.mark();
        }

        SecretKeySpec key = new SecretKeySpec(password, macAlgorithm);
        mac.init(key);

        int blockIndex = 1;
        int offset = 0;
        byte[] counter = new byte[4];
        byte[] u = new byte[mac.getMacLength()];
        byte[] t = new byte[u.length];

        while (offset < destination.capacity()) {
            writeBigEndianInt(blockIndex, counter);

            if (salt != null) {
                mac.update(salt);
                salt.reset();
            }

            mac.update(counter);
            mac.doFinal(t, 0);
            System.arraycopy(t, 0, u, 0, u.length);

            for (int i = 2; i <= iterationCount; i++) {
                mac.update(t);
                mac.doFinal(t, 0);
                for (int j = 0; j < t.length; j++) {
                    u[j] ^= t[j];
                }
            }

            destination.put(u, 0, Math.min(u.length, destination.capacity() - offset));
            offset += u.length;
            blockIndex++;
        }

        return SUCCESS;
    }

    private static void writeBigEndianInt(int value, byte[] buffer) {
        buffer[0] = (byte) (value >> 24);
        buffer[1] = (byte) (value >> 16);
        buffer[2] = (byte) (value >> 8);
        buffer[3] = (byte) value;
    }
}